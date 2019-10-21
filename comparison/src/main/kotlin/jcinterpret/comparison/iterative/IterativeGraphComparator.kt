package jcinterpret.comparison.iterative

import jcinterpret.algorithm.optimalassignment.OptimalAssignmentAlgorithmFactory
import jcinterpret.core.memory.stack.Operator
import jcinterpret.graph.execution.NodeAttributeKeys.METHODSIGNATURE
import jcinterpret.graph.execution.NodeAttributeKeys.OPERATOR
import jcinterpret.graph.isMethodCall
import jcinterpret.graph.isOperator
import jcinterpret.signature.QualifiedMethodSignature
import org.graphstream.graph.Edge
import org.graphstream.graph.Graph
import org.graphstream.graph.Node
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool
import java.util.function.Supplier

object IterativeGraphComparator {
    fun compareAsync(lhs: Graph, rhs: Graph, pool: Executor = ForkJoinPool.commonPool()): CompletableFuture<Pair<Double, Double>> = CompletableFuture.supplyAsync(Supplier<Pair<Double, Double>> { compare(lhs, rhs) }, pool)
    fun compare(lhs: Graph, rhs: Graph): Pair<Double, Double> {
        val comparator = Comparator(lhs, rhs)
        return comparator.compare()
    }

    private class Comparator(val lhs: Graph, val rhs: Graph) {
        private val lkeys = lhs.getNodeSet<Node>().toList()
        private val rkeys = rhs.getNodeSet<Node>().toList()

        private val simMap = makeSimilarityMap()

        fun compare(): Pair<Double, Double> {
            if (Math.abs(lhs.nodeCount - rhs.nodeCount) > 40) return 0.0 to 0.0

            //
            //  Initial Mappings
            //

            for ((node, candidates) in makeInitialCandidates(lhs, rhs)) {
                val nodeEdges = node.getEdgeSet<Edge>().toList()

                for (candidate in candidates) {
                    val candidateEdges = candidate.getEdgeSet<Edge>().toList()
                    val sims = Array(nodeEdges.count()) { DoubleArray(candidateEdges.count()) }

                    nodeEdges.forEachIndexed { lindex, nedge ->
                        val nopposite = nedge.getOpposite<Node>(node)

                        candidateEdges.forEachIndexed { rindex, cedge ->
                            val copposite = cedge.getOpposite<Node>(candidate)

                            if (nedge.getSourceNode<Node>().id == node.id && cedge.getSourceNode<Node>().id == candidate.id ||
                                nedge.getTargetNode<Node>().id == node.id && cedge.getTargetNode<Node>().id == candidate.id) {

                                val edgeSim = ElementMatcher.match(nedge, cedge)
                                val nodeSim = ElementMatcher.match(nopposite, copposite)

                                val sim = Math.min(edgeSim * nodeSim, 1) // Bound the sim to 1
                                sims[lindex][rindex] = 1.0-sim
                            }
                        }
                    }

                    val matches = OptimalAssignmentAlgorithmFactory.execute(sims)
                    val matchedNodes = mutableListOf<Triple<Node, Node, Double>>()
                    matches.forEachIndexed { lIndex, rIndex ->
                        if (rIndex != -1) {
                            val lhs = nodeEdges[lIndex].getOpposite<Node>(node)
                            val rhs = candidateEdges[rIndex].getOpposite<Node>(candidate)
                            val sim = 1.0 - sims[lIndex][rIndex]

                            matchedNodes.add(
                                Triple(lhs, rhs, sim)
                            )
                        }
                    }

                    val similarity: Double = matches.size.toDouble().div(Math.max(nodeEdges.size, candidateEdges.size))
                    simMap[node]!![candidate] = 1.0 - similarity
                }
            }

            val lMapped = mutableSetOf<String>()
            val rMapped = mutableSetOf<String>()

            var lastMapped = mutableListOf<Pair<Node, Node>>()
            val bestMatches = makeBestMatches()
            bestMatches.forEach { (l, r, sim) ->
                lMapped.add(l.id)
                rMapped.add(r.id)

                lastMapped.add(l to r)
            }

            //
            //  Distance Out Mapping
            //

            while (lastMapped.isNotEmpty()) {
                val pairs = lastMapped
                lastMapped = mutableListOf()

                val sims = mutableMapOf<Node, MutableMap<Node, Double>>()
                val lkeys = mutableSetOf<Node>()
                val rkeys = mutableSetOf<Node>()

                for ((lnode, rnode) in pairs) {
                    val ledges = lnode.getEdgeSet<Edge>()
                    val redges = rnode.getEdgeSet<Edge>()

                    for (ledge in ledges) {
                        val lop = ledge.getOpposite<Node>(lnode)
                        if (lMapped.contains(lop.id)) continue
                        lkeys.add(lop)

                        for (redge in redges) {
                            val rop = redge.getOpposite<Node>(rnode)
                            if (rMapped.contains(rop.id)) continue
                            rkeys.add(rop)

                            if (ledge.getSourceNode<Node>().id == lnode.id && redge.getSourceNode<Node>().id == rnode.id ||
                                ledge.getTargetNode<Node>().id == lnode.id && redge.getTargetNode<Node>().id == rnode.id) {

                                val edgeSim = ElementMatcher.match(ledge, redge).toDouble()
                                val nodeSim = if (lMapped.contains(lop.id) && rMapped.contains(rop.id)) {
                                    simMap[lop]!![rop]!!
                                } else if (lMapped.contains(lop.id) || rMapped.contains(rop.id)) {
                                    0.0
                                } else {
                                    Math.min(ElementMatcher.match(lop, rop), 1).toDouble()
                                }

                                val sim = Math.min(edgeSim * nodeSim, 1.0) // Bound the sim to 1
                                sims.getOrPut(lop) { mutableMapOf() }
                                    .put(rop, 1.0 - sim)
                            }
                        }
                    }
                }

                val bestMatches = makeBestMatches(lkeys.toList(), rkeys.toList(), sims)
                bestMatches.forEach { (l, r, sim) ->
                    lMapped.add(l.id)
                    rMapped.add(r.id)

                    lastMapped.add(l to r)
                }
            }

            val unmappedL = lkeys.filter { !lMapped.contains(it.id) }
            val unmappedR = rkeys.filter { !rMapped.contains(it.id) }

            val lsim = lMapped.count().toDouble().div(lhs.nodeCount)
            val rsim = rMapped.count().toDouble().div(rhs.nodeCount)

            return lsim to rsim
        }

        //
        //  Helpers
        //

        private fun makeBestMatches(lkeys: List<Node>, rkeys: List<Node>, sims: Map<Node, Map<Node, Double>>): List<Triple<Node, Node, Double>> {
            if (lkeys.isEmpty() || rkeys.isEmpty()) return emptyList()

            val simArr = Array(lkeys.size) { DoubleArray(rkeys.size) { 1.0 } }
            lkeys.forEachIndexed { lindex, lnode ->
                rkeys.forEachIndexed { rindex, rnode ->
                    simArr[lindex][rindex] = sims.get(lnode)?.get(rnode) ?: 1.0
                }
            }

            val matches = OptimalAssignmentAlgorithmFactory.execute(simArr)
            val matchedNodes = mutableListOf<Triple<Node, Node, Double>>()
            matches.forEachIndexed { lIndex, rIndex ->
                if (rIndex != -1) {
                    val lhs = lkeys[lIndex]
                    val rhs = rkeys[rIndex]
                    val sim = 1.0 - simArr[lIndex][rIndex]

                    matchedNodes.add(
                        Triple(lhs, rhs, sim)
                    )
                }
            }

            return matchedNodes
        }

        private fun makeBestMatches(): List<Triple<Node, Node, Double>> {
            val simArr = Array(lhs.nodeCount) { DoubleArray(rhs.nodeCount) { 1.0 } }
            lkeys.forEachIndexed { lindex, lnode ->
                rkeys.forEachIndexed { rindex, rnode ->
                    simArr[lindex][rindex] = simMap[lnode]!![rnode]!!
                }
            }

            val matches = OptimalAssignmentAlgorithmFactory.execute(simArr)
            val matchedNodes = mutableListOf<Triple<Node, Node, Double>>()
            matches.forEachIndexed { lIndex, rIndex ->
                if (rIndex != -1) {
                    val lhs = lkeys[lIndex]
                    val rhs = rkeys[rIndex]
                    val sim = 1.0 - simArr[lIndex][rIndex]

                    if (sim > 0.5) {
                        matchedNodes.add(
                            Triple(lhs, rhs, sim)
                        )
                    }
                }
            }

            return matchedNodes
        }

        private fun makeSimilarityMap(): MutableMap<Node, MutableMap<Node, Double>> {
            val simMap = mutableMapOf<Node, MutableMap<Node, Double>>()
            for (lnode in lhs.getNodeSet<Node>())
                for (rnode in rhs.getNodeSet<Node>())
                    simMap.getOrPut(lnode) { mutableMapOf() }
                        .put(rnode, 1.0)
            return simMap
        }

        private fun makeInitialCandidates(lhs: Graph, rhs: Graph): Map<Node, List<Node>> {
            val loperations = lhs.getNodeSet<Node>().filter { it.isOperator() || it.isMethodCall() }
            val roperations = rhs.getNodeSet<Node>().filter { it.isOperator() || it.isMethodCall() }

            val lgroups = loperations.groupBy {
                when {
                    it.isOperator() -> it.getAttribute<Operator>(OPERATOR)
                    it.isMethodCall() -> it.getAttribute<QualifiedMethodSignature>(METHODSIGNATURE).toString()
                    else -> TODO()
                }
            }

            val rgroups = roperations.groupBy {
                when {
                    it.isOperator() -> it.getAttribute<Operator>(OPERATOR)
                    it.isMethodCall() -> it.getAttribute<QualifiedMethodSignature>(METHODSIGNATURE).toString()
                    else -> TODO()
                }
            }

            val candidateMap = lgroups.keys.mapNotNull {
                if (rgroups.containsKey(it)) {
                    val lnodes = lgroups[it]!!
                    val rnodes = rgroups[it]!!

                    return@mapNotNull lnodes.map { it to rnodes }
                }

                return@mapNotNull null
            }.flatten().toMap()

            return candidateMap
        }
    }
}