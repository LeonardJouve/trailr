MATCH (n:Node)
SET n.location = point({
    x: n.x,
    y: n.y,
    z: n.z,
    crs: 'cartesian-3d'
});
