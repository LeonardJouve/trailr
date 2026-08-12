package ch.trailr.solver

fun coordinate(
    x: Double,
    y: Double,
    z: Double
) = Coordinate.newBuilder()
    .setX(x)
    .setY(y)
    .setZ(z)
    .build()

fun node(
    id: Int,
    x: Double,
    y: Double,
    z: Double
) = Node.newBuilder()
    .setId(id)
    .setCoordinate(coordinate(x, y, z))
    .build()

fun edge(
    uuid: String,
    from: Int,
    to: Int,
    length: Double,
    vararg coordinates: Coordinate
) = Edge.newBuilder()
    .setUuid(uuid)
    .setFromNode(from)
    .setToNode(to)
    .setLength(length)
    .addAllCoordinates(coordinates.toList())
    .build()