(() => {
  "use strict";
  const parts = location.pathname.split("/");
  const sessionId = parts[2];
  const token = parts[3];
  const endpoint = `/api/sessions/${encodeURIComponent(sessionId)}`;
  const $ = (id) => document.getElementById(id);
  const map = $("map"), tiles = $("tiles");
  const view = { lat: 37.4979, lon: 127.0276, zoom: 15 };
  let selected = null, car = null, lastState = null, watchId = null, dragging = null;
  let tileRender = 0;
  let consentInitialized = false;

  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
  const scale = () => 256 * (2 ** view.zoom);
  const worldX = (lon) => (lon + 180) / 360 * scale();
  const worldY = (lat) => {
    const sin = Math.sin(clamp(lat, -85.0511, 85.0511) * Math.PI / 180);
    return (0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI)) * scale();
  };
  const latLon = (x, y) => ({
    lon: x / scale() * 360 - 180,
    lat: Math.atan(Math.sinh(Math.PI * (1 - 2 * y / scale()))) * 180 / Math.PI
  });
  const origin = () => ({ x: worldX(view.lon) - map.clientWidth / 2, y: worldY(view.lat) - map.clientHeight / 2 });
  const pointAt = (px, py) => { const o = origin(); return latLon(o.x + px, o.y + py); };
  const haversineKm = (a, b) => {
    const r = Math.PI / 180, dLat = (b.lat - a.lat) * r, dLon = (b.lon - a.lon) * r;
    const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * r) * Math.cos(b.lat * r) * Math.sin(dLon / 2) ** 2;
    return 6371 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
  };

  function placeMarker(id, position) {
    const element = $(id);
    if (!position) { element.hidden = true; return; }
    const o = origin();
    element.style.left = `${worldX(position.lon) - o.x}px`;
    element.style.top = `${worldY(position.lat) - o.y}px`;
    element.hidden = false;
  }

  function renderMap() {
    const currentRender = ++tileRender;
    let loadedTiles = 0;
    $("map-fallback").hidden = true;
    const o = origin(), n = 2 ** view.zoom;
    const minX = Math.floor(o.x / 256), maxX = Math.floor((o.x + map.clientWidth) / 256);
    const minY = Math.floor(o.y / 256), maxY = Math.floor((o.y + map.clientHeight) / 256);
    const fragment = document.createDocumentFragment();
    for (let y = minY; y <= maxY; y++) for (let x = minX; x <= maxX; x++) {
      if (y < 0 || y >= n) continue;
      const tile = document.createElement("img");
      tile.alt = "";
      tile.loading = "lazy";
      tile.referrerPolicy = "origin";
      tile.src = `https://tile.openstreetmap.org/${view.zoom}/${((x % n) + n) % n}/${y}.png`;
      tile.addEventListener("load", () => {
        if (currentRender !== tileRender) return;
        loadedTiles++;
        $("map-fallback").hidden = true;
      }, { once: true });
      tile.addEventListener("error", () => {
        if (currentRender === tileRender && loadedTiles === 0) $("map-fallback").hidden = false;
        tile.remove();
      }, { once: true });
      tile.style.left = `${x * 256 - o.x}px`;
      tile.style.top = `${y * 256 - o.y}px`;
      fragment.append(tile);
    }
    tiles.replaceChildren(fragment);
    setTimeout(() => {
      if (currentRender === tileRender && loadedTiles === 0) $("map-fallback").hidden = false;
    }, 3000);
    placeMarker("pickup-pin", selected);
    placeMarker("car-pin", car);
  }

  function setSelected(position) {
    selected = { lat: clamp(position.lat, -85.0511, 85.0511), lon: clamp(position.lon, -180, 180) };
    $("coordinates").textContent = `선택한 지점: ${selected.lat.toFixed(5)}, ${selected.lon.toFixed(5)}`;
    $("submit-pickup").disabled = false;
    placeMarker("pickup-pin", selected);
  }

  map.addEventListener("pointerdown", (event) => {
    map.setPointerCapture(event.pointerId);
    dragging = { x: event.clientX, y: event.clientY, centerX: worldX(view.lon), centerY: worldY(view.lat), moved: false };
  });
  map.addEventListener("pointermove", (event) => {
    if (!dragging) return;
    const dx = event.clientX - dragging.x, dy = event.clientY - dragging.y;
    if (Math.abs(dx) + Math.abs(dy) > 6) dragging.moved = true;
    if (!dragging.moved) return;
    const next = latLon(dragging.centerX - dx, dragging.centerY - dy);
    view.lat = next.lat; view.lon = next.lon;
    renderMap();
  });
  map.addEventListener("pointerup", (event) => {
    if (!dragging) return;
    if (!dragging.moved) {
      const rect = map.getBoundingClientRect();
      setSelected(pointAt(event.clientX - rect.left, event.clientY - rect.top));
    }
    dragging = null;
  });
  map.addEventListener("pointercancel", () => { dragging = null; });
  $("zoom-in").addEventListener("click", () => { view.zoom = clamp(view.zoom + 1, 3, 18); renderMap(); });
  $("zoom-out").addEventListener("click", () => { view.zoom = clamp(view.zoom - 1, 3, 18); renderMap(); });
  window.addEventListener("resize", renderMap);

  async function request(action, body) {
    const response = await fetch(`${endpoint}${action}`, {
      method: body === undefined ? "GET" : "POST",
      headers: { Authorization: `Bearer ${token}`, ...(body === undefined ? {} : { "Content-Type": "application/json" }) },
      body: body === undefined ? undefined : JSON.stringify(body),
      cache: "no-store"
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || "요청을 처리하지 못했습니다.");
    return data;
  }

  const statusText = {
    invited: "운전자의 초대를 받았습니다. 승차 지점을 지정해 주세요.",
    pickup_pending: "승차 지점을 보냈습니다. 차량 경로를 준비하는 중입니다.",
    active: "승차 지점이 확정됐습니다. 차량 위치를 확인할 수 있습니다.",
    finished: "픽업이 완료되어 위치 공유가 종료됐습니다.",
    expired: "초대 유효 시간이 지나 위치 공유가 종료됐습니다."
  };

  function showState(state) {
    lastState = state;
    const canMessage = ["pickup_pending", "active"].includes(state.status);
    $("message-card").hidden = !canMessage;
    $("message-list").textContent = (state.messages || []).slice(-10).map(item =>
      `${item.sender === "driver" ? "운전자" : "나"}: ${item.text}`).join("\n") || "아직 메시지가 없습니다.";
    $("contact-name").textContent = `${state.contactName}님 픽업`;
    $("pickup-time").textContent = state.pickupTime || "";
    $("session-status").textContent = statusText[state.status] || state.status;
    if (state.pickup && !selected) {
      selected = { lat: state.pickup.lat, lon: state.pickup.lon };
      $("coordinates").textContent = `지정한 지점: ${selected.lat.toFixed(5)}, ${selected.lon.toFixed(5)}`;
      $("place-label").value = state.pickup.label;
      $("submit-pickup").disabled = false;
    }
    if (state.pickup && !consentInitialized) {
      $("share-location").checked = state.passengerLocationSharing;
      consentInitialized = true;
      if (state.passengerLocationSharing) shareCurrentLocation();
    }
    car = state.driverLocation;
    placeMarker("pickup-pin", selected);
    placeMarker("car-pin", car);
    if (car && state.pickup) {
      const distance = haversineKm(car, state.pickup);
      const eta = state.etaSeconds === null ? "" : ` · 예상 ${Math.max(1, Math.ceil(state.etaSeconds / 60))}분`;
      $("car-status").textContent = `차량과 승차 지점 직선거리 약 ${distance.toFixed(1)}km${eta}`;
    } else {
      $("car-status").textContent = "";
    }
    if (["finished", "expired"].includes(state.status)) {
      $("submit-pickup").disabled = true;
      $("share-location").checked = false;
      $("share-location").disabled = true;
      if (watchId !== null) navigator.geolocation.clearWatch(watchId);
      watchId = null;
    }
  }

  async function poll() {
    try { showState(await request("")); }
    catch (error) { $("session-status").textContent = error.message; }
  }

  function shareCurrentLocation() {
    if (watchId !== null || !$("share-location").checked || !lastState?.passengerLocationSharing || !navigator.geolocation) return;
    if (!window.isSecureContext) {
      $("feedback").textContent = "현재 위치 공유에는 HTTPS가 필요합니다. 지도에서 승차 지점을 지정하는 기능은 계속 사용할 수 있습니다.";
      return;
    }
    watchId = navigator.geolocation.watchPosition(async (position) => {
      if (!$("share-location").checked || !lastState?.passengerLocationSharing) return;
      try {
        await request("/location", { lat: position.coords.latitude, lon: position.coords.longitude });
      } catch (error) { $("feedback").textContent = error.message; }
    }, (error) => { $("feedback").textContent = `현재 위치를 공유하지 못했습니다: ${error.message}`; },
    { enableHighAccuracy: true, maximumAge: 10000, timeout: 15000 });
  }

  $("locate").addEventListener("click", () => {
    if (!navigator.geolocation || !window.isSecureContext) {
      $("feedback").textContent = "내 위치 기능은 HTTPS 또는 localhost에서 사용할 수 있습니다. 지도를 눌러 지점을 선택해 주세요.";
      return;
    }
    navigator.geolocation.getCurrentPosition((position) => {
      view.lat = position.coords.latitude; view.lon = position.coords.longitude;
      setSelected({ lat: view.lat, lon: view.lon }); renderMap();
    }, (error) => { $("feedback").textContent = error.message; });
  });

  $("share-location").addEventListener("change", async () => {
    consentInitialized = true;
    if (!lastState?.pickup || ["finished", "expired"].includes(lastState.status)) return;
    const enabled = $("share-location").checked;
    if (!enabled && watchId !== null) {
      navigator.geolocation.clearWatch(watchId);
      watchId = null;
    }
    $("share-location").disabled = true;
    try {
      showState(await request("/location-consent", { enabled }));
      if (enabled) {
        shareCurrentLocation();
        $("feedback").textContent = "현재 위치 공유를 다시 시작했습니다.";
      } else {
        $("feedback").textContent = "현재 위치 공유를 중단하고 서버에 저장된 위치를 삭제했습니다.";
      }
    } catch (error) {
      $("share-location").checked = !enabled;
      $("feedback").textContent = `위치 공유 상태를 변경하지 못했습니다: ${error.message}`;
    } finally {
      $("share-location").disabled = ["finished", "expired"].includes(lastState?.status);
    }
  });

  $("submit-pickup").addEventListener("click", async () => {
    const label = $("place-label").value.trim();
    if (!selected || !label) { $("feedback").textContent = "지점을 선택하고 장소 이름을 입력해 주세요."; return; }
    const shareLiveLocation = $("share-location").checked;
    try {
      $("submit-pickup").disabled = true;
      showState(await request("/pickup", { lat: selected.lat, lon: selected.lon, label }));
      $("feedback").textContent = "승차 지점을 운전자에게 보냈습니다.";
      if (shareLiveLocation) {
        try {
          showState(await request("/location-consent", { enabled: true }));
          shareCurrentLocation();
          $("feedback").textContent = "승차 지점을 보냈고 현재 위치 공유를 시작했습니다.";
        } catch (error) {
          $("share-location").checked = false;
          $("feedback").textContent = `승차 지점은 보냈지만 현재 위치 공유를 시작하지 못했습니다: ${error.message}`;
        }
      }
    } catch (error) {
      $("feedback").textContent = error.message;
    } finally {
      $("submit-pickup").disabled = ["finished", "expired"].includes(lastState?.status);
    }
  });

  $("send-message").addEventListener("click", async () => {
    const input = $("message-input"), text = input.value.trim();
    if (!text) { $("message-feedback").textContent = "메시지를 입력해 주세요."; return; }
    $("send-message").disabled = true;
    try {
      showState(await request("/message", { text }));
      input.value = "";
      $("message-feedback").textContent = "메시지를 보냈습니다.";
    } catch (error) { $("message-feedback").textContent = error.message; }
    finally { $("send-message").disabled = false; }
  });
  renderMap();
  poll();
  setInterval(poll, 3000);
})();
