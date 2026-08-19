package ch.trailr.solver

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrailServiceTest {
    private val service = TrailService()

    @Test
    fun `finds exact length square`() = runBlocking {
        val request = SolveTourRequest.newBuilder()
            .setOriginId(1)
            .setTargetLength(40.0)
            .setTargetElevation(5.0)
            .addAllNodes(
                listOf(
                    node(1, 0.0, 0.0, 0.0),
                    node(2, 10.0, 0.0, 2.0),
                    node(3, 10.0, 10.0, 5.0),
                    node(4, 0.0, 10.0, 3.0),
                )
            )
            .addAllEdges(
                listOf(
                    edge(
                        "e1", 1, 2, 10.0,
                        coordinate(0.0, 0.0, 0.0),
                        coordinate(10.0, 0.0, 2.0)
                    ),
                    edge(
                        "e2", 2, 3, 10.0,
                        coordinate(10.0, 0.0, 2.0),
                        coordinate(10.0, 10.0, 5.0)
                    ),
                    edge(
                        "e3", 3, 4, 10.0,
                        coordinate(10.0, 10.0, 5.0),
                        coordinate(0.0, 10.0, 3.0)
                    ),
                    edge(
                        "e4", 4, 1, 10.0,
                        coordinate(0.0, 10.0, 3.0),
                        coordinate(0.0, 0.0, 0.0)
                    )
                )
            )
            .setLengthPenaltyWeight(1.0)
            .setElevationPenaltyWeight(5.0)
            .setRepeatPenaltyWeight(5.0)
            .build()

        val response = service.solveTour(request)

        assertTrue(response.found)
        assertEquals(40.0, response.length, 1e-6)
        assertEquals(5.0, response.elevation, 1e-6)

        assertEquals(1, response.nodeIdsList.first())
        assertEquals(1, response.nodeIdsList.last())
        assertEquals(
            setOf("e1", "e2", "e3", "e4"),
            response.edgeIdsList.toSet()
        )
    }

    @Test
    fun `same physical edge traversed both directions counts twice`() = runBlocking {
        val request = SolveTourRequest.newBuilder()
            .setOriginId(1)
            .setTargetLength(20.0)
            .setTargetElevation(0.0)
            .addAllNodes(
                listOf(
                    node(1, 0.0, 0.0, 0.0),
                    node(2, 10.0, 0.0, 0.0)
                )
            )
            .addEdges(
                edge(
                    "e1",
                    1,
                    2,
                    10.0,
                    coordinate(0.0, 0.0, 0.0),
                    coordinate(10.0, 0.0, 0.0)
                )
            )
            .setLengthPenaltyWeight(1.0)
            .setElevationPenaltyWeight(5.0)
            .setRepeatPenaltyWeight(5.0)
            .build()

        val response = service.solveTour(request)

        assertTrue(response.found)
        assertEquals(20.0, response.length, 1e-6)

        assertEquals(
            listOf(1, 2, 1),
            response.nodeIdsList
        )

        assertEquals(
            listOf("e1", "e1"),
            response.edgeIdsList
        )
    }

    @Test
    fun `does not select disconnected cycle`() = runBlocking {
        val request = SolveTourRequest.newBuilder()
            .setOriginId(1)
            .setTargetLength(40.0)
            .setTargetElevation(0.0)
            .addAllNodes(
                listOf(
                    node(1, 0.0, 0.0, 0.0),
                    node(2, 10.0, 0.0, 0.0),
                    node(3, 5.0, 10.0, 0.0),
                    node(4, 100.0, 0.0, 0.0),
                    node(5, 110.0, 0.0, 0.0),
                )
            )
            .addAllEdges(
                listOf(
                    // Connected component
                    edge(
                        "e1", 1, 2, 10.0,
                        coordinate(0.0, 0.0, 0.0),
                        coordinate(10.0, 0.0, 0.0)
                    ),
                    edge(
                        "e2", 2, 3, 10.0,
                        coordinate(10.0, 0.0, 0.0),
                        coordinate(5.0, 10.0, 0.0)
                    ),
                    edge(
                        "e3", 3, 1, 10.0,
                        coordinate(5.0, 10.0, 0.0),
                        coordinate(0.0, 0.0, 0.0)
                    ),

                    // Disconnected component
                    edge(
                        "e4", 4, 5, 10.0,
                        coordinate(100.0, 0.0, 0.0),
                        coordinate(110.0, 0.0, 0.0)
                    )
                )
            )
            .setLengthPenaltyWeight(1.0)
            .setElevationPenaltyWeight(5.0)
            .setRepeatPenaltyWeight(5.0)
            .build()

        val response = service.solveTour(request)

        assertTrue(response.found)

        assertTrue(
            response.nodeIdsList.all {
                it in setOf(1, 2, 3)
            }
        )
    }

    @Test
    fun `returns not found when origin has no edges`() = runBlocking {
        val request = SolveTourRequest.newBuilder()
            .setOriginId(1)
            .setTargetLength(100.0)
            .setTargetElevation(10.0)
            .addNodes(node(1, 0.0, 0.0, 0.0))
            .setLengthPenaltyWeight(1.0)
            .setElevationPenaltyWeight(5.0)
            .setRepeatPenaltyWeight(5.0)
            .build()

        val response = service.solveTour(request)

        assertFalse(response.found)
    }

    @Test
    fun `allows node to be visited multiple times`() = runBlocking {
        val request = SolveTourRequest.newBuilder()
            .setOriginId(1)
            .setTargetLength(50.0)
            .setTargetElevation(0.0)
            .addAllNodes(
                listOf(
                    node(1, 0.0, 0.0, 0.0),
                    node(2, 10.0, 0.0, 0.0),
                    node(3, 20.0, 0.0, 0.0),
                    node(4, 20.0, 10.0, 0.0),
                )
            )
            .addAllEdges(
                listOf(
                    edge(
                        "e1", 1, 2, 10.0,
                        coordinate(0.0, 0.0, 0.0),
                        coordinate(10.0, 0.0, 0.0)
                    ),
                    edge(
                        "e2", 2, 3, 10.0,
                        coordinate(10.0, 0.0, 0.0),
                        coordinate(20.0, 0.0, 0.0)
                    ),
                    edge(
                        "e3", 2, 4, 10.0,
                        coordinate(10.0, 0.0, 0.0),
                        coordinate(20.0, 10.0, 0.0)
                    ),
                    edge(
                        "e4", 4, 1, 10.0,
                        coordinate(20.0, 10.0, 0.0),
                        coordinate(0.0, 0.0, 0.0)
                    )
                )
            )
            .setLengthPenaltyWeight(1.0)
            .setElevationPenaltyWeight(5.0)
            .setRepeatPenaltyWeight(0.0)
            .build()

        val response = service.solveTour(request)

        assertTrue(response.found)

        assertEquals(50.0, response.length, 1e-6)
        assertEquals(1, response.nodeIdsList.first())
        assertEquals(1, response.nodeIdsList.last())
        assertEquals(2, response.nodeIdsList.count { it == 2 })
        assertEquals(
            listOf("e1", "e2", "e2", "e3", "e4").sorted(),
            response.edgeIdsList.sorted()
        )
    }

    @Test
    fun `exponential length penalty prefers repeating long edges over tiny trail`() = runBlocking {
        val request = SolveTourRequest.newBuilder()
            .setOriginId(1)
            .setTargetLength(2000.0)
            .setTargetElevation(0.0)
            .addAllNodes(
                listOf(
                    node(1, 0.0, 0.0, 0.0),
                    node(2, 1000.0, 0.0, 0.0),
                    node(3, 5.0, 0.0, 0.0),
                )
            )
            .addAllEdges(
                listOf(
                    edge(
                        "long", 1, 2, 1000.0,
                        coordinate(0.0, 0.0, 0.0),
                        coordinate(1000.0, 0.0, 0.0)
                    ),
                    edge(
                        "tiny", 1, 3, 10.0,
                        coordinate(0.0, 0.0, 0.0),
                        coordinate(10.0, 0.0, 0.0)
                    )
                )
            )
            .setLengthPenaltyWeight(1.0)
            .setElevationPenaltyWeight(0.0)
            .setRepeatPenaltyWeight(5.0)
            .setExponentialLengthPenaltyWeight(1.0)
            .build()

        val response = service.solveTour(request)

        assertTrue(response.found)
        assertEquals(2000.0, response.length, 1e-6)
        assertEquals(listOf(1, 2, 1), response.nodeIdsList)
        assertEquals(listOf("long", "long"), response.edgeIdsList)
    }
}