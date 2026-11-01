import json
import threading
import time
import unittest
from http.server import ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from server import SessionStore, make_handler


class PickupApiTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.store = SessionStore()
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(cls.store))
        cls.base = f"http://127.0.0.1:{cls.server.server_port}"
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=2)

    def api(self, method, path, body=None, token=None):
        headers = {}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        data = None
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = Request(self.base + path, data=data, method=method, headers=headers)
        try:
            with urlopen(request, timeout=3) as response:
                return response.status, json.loads(response.read())
        except HTTPError as error:
            return error.code, json.loads(error.read())

    def create(self):
        status, created = self.api("POST", "/api/sessions", {"contactName": "Demo Passenger", "pickupTime": "18:30"})
        self.assertEqual(status, 201)
        self.assertNotEqual(created["driverToken"], created["passengerToken"])
        return created

    def test_authorization_and_full_lifecycle(self):
        created = self.create()
        path = f'/api/sessions/{created["id"]}'
        driver = created["driverToken"]
        passenger = created["passengerToken"]

        self.assertEqual(self.api("GET", path)[0], 401)
        self.assertEqual(self.api("GET", path, token="wrong")[0], 401)
        self.assertEqual(self.api("GET", path, token=driver)[1]["status"], "invited")
        self.assertEqual(self.api("POST", path + "/pickup", {"lat": 37.5, "lon": 127.0, "label": "Station"}, driver)[0], 403)
        self.assertEqual(self.api("POST", path + "/location", {"lat": 37.5, "lon": 127.0}, passenger)[0], 409)

        status, proposed = self.api("POST", path + "/pickup", {"lat": 37.5, "lon": 127.0, "label": "Station"}, passenger)
        self.assertEqual(status, 200)
        self.assertEqual(proposed["status"], "pickup_pending")
        self.assertFalse(proposed["passengerLocationSharing"])
        revision = proposed["pickup"]["revision"]
        self.assertFalse(proposed["pickup"]["confirmed"])
        self.assertEqual(self.api("POST", path + "/location", {"lat": 37.5, "lon": 127.0}, passenger)[0], 409)
        self.assertEqual(self.api("POST", path + "/confirm", {"revision": revision + 1}, driver)[0], 409)
        self.assertEqual(self.api("POST", path + "/confirm", {"revision": revision}, passenger)[0], 403)

        status, active = self.api("POST", path + "/confirm", {"revision": revision}, driver)
        self.assertEqual(status, 200)
        self.assertEqual(active["status"], "active")
        self.assertTrue(active["pickup"]["confirmed"])
        self.assertEqual(self.api("POST", path + "/location", {"lat": 37.49, "lon": 127.02, "etaSeconds": 300}, driver)[0], 200)
        self.assertEqual(self.api("POST", path + "/location-consent", {"enabled": True}, passenger)[0], 200)
        self.assertEqual(self.api("POST", path + "/location", {"lat": 37.5, "lon": 127.0}, passenger)[0], 200)
        self.assertEqual(self.api("GET", path, token=driver)[1]["passengerLocation"]["lat"], 37.5)
        self.assertIsNone(self.api("GET", path, token=passenger)[1]["passengerLocation"])
        self.assertEqual(self.api("GET", path, token=passenger)[1]["etaSeconds"], 300)

        status, revised = self.api("POST", path + "/pickup", {"lat": 37.51, "lon": 127.01, "label": "New point"}, passenger)
        self.assertEqual(status, 200)
        self.assertEqual(revised["status"], "pickup_pending")
        self.assertEqual(revised["pickup"]["revision"], revision + 1)
        self.assertIsNone(revised["etaSeconds"])

        self.assertEqual(self.api("POST", path + "/finish", {}, passenger)[0], 403)
        status, finished = self.api("POST", path + "/finish", {}, driver)
        self.assertEqual(status, 200)
        self.assertEqual(finished["status"], "finished")
        self.assertIsNone(finished["driverLocation"])
        self.assertIsNone(finished["passengerLocation"])
        self.assertEqual(self.api("POST", path + "/location", {"lat": 37.5, "lon": 127.0}, driver)[0], 409)

    def test_input_validation_and_expiry(self):
        self.assertEqual(self.api("POST", "/api/sessions", {"contactName": " "})[0], 400)
        created = self.create()
        path = f'/api/sessions/{created["id"]}'
        self.assertEqual(self.api("POST", path + "/pickup", {"lat": 91, "lon": 127, "label": "X"}, created["passengerToken"])[0], 400)
        with self.store._lock:
            self.store._sessions[created["id"]]["expires_epoch"] = time.time() - 1
        status, expired = self.api("GET", path, token=created["driverToken"])
        self.assertEqual(status, 200)
        self.assertEqual(expired["status"], "expired")
        self.assertEqual(self.api("POST", path + "/confirm", {"revision": 1}, created["driverToken"])[0], 409)

    def test_passenger_can_revoke_and_resume_location_sharing(self):
        created = self.create()
        path = f'/api/sessions/{created["id"]}'
        driver = created["driverToken"]
        passenger = created["passengerToken"]
        status, point_only = self.api("POST", path + "/pickup", {"lat": 37.5, "lon": 127.0, "label": "Station"}, passenger)
        self.assertEqual(status, 200)
        self.assertFalse(point_only["passengerLocationSharing"])
        self.assertEqual(self.api("POST", path + "/location", {"lat": 37.501, "lon": 127.001}, passenger)[0], 409)
        self.assertEqual(self.api("POST", path + "/location-consent", {"enabled": True}, passenger)[0], 200)
        self.api("POST", path + "/location", {"lat": 37.501, "lon": 127.001}, passenger)
        self.assertIsNotNone(self.api("GET", path, token=driver)[1]["passengerLocation"])

        self.assertEqual(self.api("POST", path + "/location-consent", {"enabled": False}, driver)[0], 403)
        self.assertEqual(self.api("POST", path + "/location-consent", {"enabled": "false"}, passenger)[0], 400)
        status, revoked = self.api("POST", path + "/location-consent", {"enabled": False}, passenger)
        self.assertEqual(status, 200)
        self.assertFalse(revoked["passengerLocationSharing"])
        self.assertIsNone(self.api("GET", path, token=driver)[1]["passengerLocation"])
        self.assertEqual(self.api("POST", path + "/location", {"lat": 37.502, "lon": 127.002}, passenger)[0], 409)

        self.api("POST", path + "/pickup", {"lat": 37.51, "lon": 127.01, "label": "New point"}, passenger)
        self.assertFalse(self.api("GET", path, token=passenger)[1]["passengerLocationSharing"])
        status, resumed = self.api("POST", path + "/location-consent", {"enabled": True}, passenger)
        self.assertEqual(status, 200)
        self.assertTrue(resumed["passengerLocationSharing"])
        self.assertEqual(self.api("POST", path + "/location", {"lat": 37.502, "lon": 127.002}, passenger)[0], 200)

    def test_passenger_page_has_privacy_headers(self):
        created = self.create()
        path = f'/p/{created["id"]}/{created["passengerToken"]}'
        with urlopen(self.base + path, timeout=3) as response:
            self.assertEqual(response.status, 200)
            self.assertEqual(response.headers["Referrer-Policy"], "no-referrer")
            self.assertIn("픽업메이트", response.read().decode("utf-8"))


if __name__ == "__main__":
    unittest.main()
