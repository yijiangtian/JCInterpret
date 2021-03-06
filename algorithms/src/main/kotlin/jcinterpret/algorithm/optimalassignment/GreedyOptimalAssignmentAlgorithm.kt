package jcinterpret.algorithm.optimalassignment

object GreedyOptimalAssignmentAlgorithm: OptimalAssignmentAlgorithm() {

    override fun execute(costs: Array<DoubleArray>): IntArray {
        return GreedyOptimalAssignmentAlgorithm.execute(costs)
    }

    override fun <X, Y> execute(
        litems: List<X>,
        ritems: List<Y>,
        costs: Array<DoubleArray>,
        matchThreshold: Double
    ): OptimalAssignmentResult<X, Y> {

        val algo = GreedyAlgorithm(costs)
        val result = algo.execute()

        return makeResult(litems, ritems, costs, matchThreshold, result)
    }
}