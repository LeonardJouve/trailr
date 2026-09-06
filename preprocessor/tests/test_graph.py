import unittest

import geopandas as gpd
from shapely.geometry import LineString

from preprocessor.graph import validate_input


class GeometryNormalizationTest(unittest.TestCase):
    def _frame(self, geometries, **data) -> gpd.GeoDataFrame:
        return gpd.GeoDataFrame(
            data=data,
            geometry=geometries,
            crs="EPSG:2056",
        )

    def test_validate_input_rejects_none_geometry(self):
        trails = self._frame(
            [LineString([(0, 0, 5), (1, 1, 5)]), None],
            uuid=["a", "b"],
        )

        with self.assertRaises(ValueError):
            validate_input(trails, {"uuid": "uuid:string"}, "ski")

    def test_validate_input_still_rejects_raw_two_dimensional_lines(self):
        trails = self._frame([LineString([(0, 0), (1, 1)])], uuid=["a"])

        with self.assertRaises(ValueError):
            validate_input(trails, {"uuid": "uuid:string"}, "ski")


if __name__ == "__main__":
    unittest.main()
