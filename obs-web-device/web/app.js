const STR = {
  es: {
    title: "Conectar",
    room: "Código de sala",
    password: "Contraseña",
    name: "Nombre",
    camera: "Cámara",
    mic: "Micrófono",
    quality: "Calidad",
    back: "Trasera",
    front: "Frontal",
    refresh: "Actualizar",
    mirror: "Espejo",
    voice: "Reducir eco y ruido",
    start: "Iniciar",
    stop: "Detener",
    onAir: "En el aire",
    hint: "Usa el código y la contraseña que muestra NexoCam en OBS. 720p es el mejor equilibrio.",
    noneCam: "Sin cámara",
    noneMic: "Sin micrófono",
    device: "Dispositivo",
    qMax: "1080p",
    qHigh: "720p",
    qLow: "480p, menos retraso",
    needRoom: "Escribe el código de sala de OBS.",
    needPassword: "Escribe la contraseña de la fuente NexoCam en OBS.",
    needDevice: "Elige una cámara, un micrófono, o los dos.",
    starting: "Conectando…",
    live: "En el aire",
    reconnecting: "Reconectando…",
    badRoom: "Sala o contraseña incorrectas, o OBS no está conectado.",
    denied: "Permiso denegado. Activa cámara o micrófono para este sitio.",
    missing: "No encontré ese dispositivo.",
    busy: "Otra aplicación está usando la cámara o el micrófono.",
    failed: "No se pudo abrir el dispositivo.",
    stopped: "Detenido",
    noWs: "Falta la URL del puente (config.js → wsUrl).",
  },
  en: {
    title: "Connect",
    room: "Room code",
    password: "Password",
    name: "Name",
    camera: "Camera",
    mic: "Microphone",
    quality: "Quality",
    back: "Back",
    front: "Front",
    refresh: "Refresh",
    mirror: "Mirror",
    voice: "Reduce echo and noise",
    start: "Start",
    stop: "Stop",
    onAir: "On air",
    hint: "Use the room code and password shown by NexoCam in OBS. 720p is the best balance.",
    noneCam: "No camera",
    noneMic: "No microphone",
    device: "Device",
    qMax: "1080p",
    qHigh: "720p",
    qLow: "480p, lower delay",
    needRoom: "Type the room code from OBS.",
    needPassword: "Type the password from the NexoCam source in OBS.",
    needDevice: "Pick a camera, a microphone, or both.",
    starting: "Connecting…",
    live: "On air",
    reconnecting: "Reconnecting…",
    badRoom: "Wrong room or password, or OBS is not connected.",
    denied: "Permission denied. Allow the camera or microphone for this site.",
    missing: "That device was not found.",
    busy: "Another app is using the camera or microphone.",
    failed: "Could not open the device.",
    stopped: "Stopped",
    noWs: "Bridge URL missing (config.js → wsUrl).",
  },
};

const PRESETS = {
  max: { w: 1920, h: 1080, fps: 30, q: 0.72 },
  high: { w: 1280, h: 720, fps: 30, q: 0.76 },
  low: { w: 854, h: 480, fps: 24, q: 0.62 },
};

const $ = (id) => document.getElementById(id);
const roomInput = $("room");
const passwordInput = $("password");
const nameInput = $("device-name");
const cameraSelect = $("camera");
const micSelect = $("mic");
const qualitySelect = $("quality");
const mirrorInput = $("mirror");
const voiceInput = $("voice");
const preview = $("preview");
const statusEl = $("status");
const statsEl = $("stats");
const levelEl = $("level");

const state = {
  lang: "es",
  want: false,
  ready: false,
  ws: null,
  stream: null,
  audioCtx: null,
  timer: 0,
  busy: false,
  wakeLock: null,
  bytes: 0,
  frames: 0,
  statAt: 0,
  canvas: document.createElement("canvas"),
  ctx2d: null,
};

function t(key) {
  return (STR[state.lang] && STR[state.lang][key]) || STR.es[key] || key;
}

function defaultName() {
  const ua = navigator.userAgent;
  if (/iPhone/.test(ua)) return "iPhone";
  if (/iPad/.test(ua)) return "iPad";
  if (/Android/.test(ua)) return "Android";
  if (/Windows/.test(ua)) return "Windows";
  if (/Mac/.test(ua)) return "Mac";
  return "Browser";
}

function setStatus(text) {
  statusEl.textContent = text;
}

function applyLang() {
  document.documentElement.lang = state.lang;
  document.querySelectorAll("[data-i18n]").forEach((el) => {
    el.textContent = t(el.getAttribute("data-i18n"));
  });
  const current = qualitySelect.value || "high";
  qualitySelect.innerHTML = "";
  for (const id of ["high", "max", "low"]) {
    const option = document.createElement("option");
    option.value = id;
    option.textContent = t(id === "max" ? "qMax" : id === "low" ? "qLow" : "qHigh");
    qualitySelect.appendChild(option);
  }
  qualitySelect.value = current;
  $("lang-es").classList.toggle("active", state.lang === "es");
  $("lang-en").classList.toggle("active", state.lang === "en");
  refreshDevices();
}

function roomCode() {
  return roomInput.value.trim().toUpperCase().replace(/[^A-Z0-9]/g, "");
}

function passwordValue() {
  return passwordInput.value;
}

function preset() {
  return PRESETS[qualitySelect.value] || PRESETS.high;
}

function fillSelect(select, devices, noneLabel) {
  const previous = select.value;
  select.innerHTML = "";
  const none = document.createElement("option");
  none.value = "__none__";
  none.textContent = noneLabel;
  select.appendChild(none);
  devices.forEach((device, index) => {
    const option = document.createElement("option");
    option.value = device.deviceId;
    option.textContent = device.label || `${t("device")} ${index + 1}`;
    select.appendChild(option);
  });
  const stillThere = [...select.options].some((option) => option.value === previous && previous);
  if (stillThere) select.value = previous;
  else if (devices.length) select.value = devices[0].deviceId;
  else select.value = "__none__";
}

async function refreshDevices() {
  if (!navigator.mediaDevices || !navigator.mediaDevices.enumerateDevices) return;
  const list = await navigator.mediaDevices.enumerateDevices();
  fillSelect(cameraSelect, list.filter((d) => d.kind === "videoinput"), t("noneCam"));
  fillSelect(micSelect, list.filter((d) => d.kind === "audioinput"), t("noneMic"));
}

function videoConstraints(facing) {
  const chosen = preset();
  const video = {
    width: { ideal: chosen.w },
    height: { ideal: chosen.h },
    frameRate: { ideal: chosen.fps },
  };
  if (facing) video.facingMode = { ideal: facing };
  else if (cameraSelect.value && cameraSelect.value !== "__none__")
    video.deviceId = { exact: cameraSelect.value };
  return video;
}

function audioConstraints() {
  if (micSelect.value === "__none__") return false;
  const audio = {
    echoCancellation: voiceInput.checked,
    noiseSuppression: voiceInput.checked,
    autoGainControl: voiceInput.checked,
    channelCount: 1,
  };
  if (micSelect.value) audio.deviceId = { exact: micSelect.value };
  return audio;
}

function stopTracks() {
  if (state.stream) {
    state.stream.getTracks().forEach((track) => track.stop());
    state.stream = null;
  }
  preview.srcObject = null;
}

function mediaError(error) {
  const name = error && error.name;
  if (name === "NotAllowedError" || name === "SecurityError") return t("denied");
  if (name === "NotFoundError" || name === "OverconstrainedError") return t("missing");
  if (name === "NotReadableError") return t("busy");
  return t("failed");
}

async function openMedia(facing) {
  stopTracks();
  const constraints = {
    video: cameraSelect.value === "__none__" && !facing ? false : videoConstraints(facing),
    audio: audioConstraints(),
  };
  if (!constraints.video && !constraints.audio)
    throw new Error("need-device");
  let stream;
  try {
    stream = await navigator.mediaDevices.getUserMedia(constraints);
  } catch (error) {
    if (constraints.video && constraints.video.deviceId) {
      constraints.video.deviceId = { ideal: cameraSelect.value };
      stream = await navigator.mediaDevices.getUserMedia(constraints);
    } else {
      throw error;
    }
  }
  state.stream = stream;
  const videoTrack = stream.getVideoTracks()[0];
  if (videoTrack) {
    preview.srcObject = stream;
    preview.classList.toggle("mirror", mirrorInput.checked);
    await preview.play().catch(() => {});
    const id = videoTrack.getSettings().deviceId;
    if (id) {
      await refreshDevices();
      if ([...cameraSelect.options].some((option) => option.value === id))
        cameraSelect.value = id;
    }
  } else {
    preview.removeAttribute("src");
    preview.srcObject = null;
  }
  await attachAudio(stream);
}

async function attachAudio(stream) {
  const track = stream.getAudioTracks()[0];
  if (!track) return;
  if (!state.audioCtx) {
    try {
      state.audioCtx = new AudioContext({ sampleRate: 48000 });
    } catch (e) {
      state.audioCtx = new AudioContext();
    }
  }
  await state.audioCtx.resume();
  const source = state.audioCtx.createMediaStreamSource(new MediaStream([track]));
  const mute = state.audioCtx.createGain();
  mute.gain.value = 0;
  const analyser = state.audioCtx.createAnalyser();
  analyser.fftSize = 256;
  source.connect(analyser);
  state.analyser = analyser;

  let sent = false;
  if (state.audioCtx.audioWorklet) {
    try {
      await state.audioCtx.audioWorklet.addModule("/pcm-worklet.js");
      const node = new AudioWorkletNode(state.audioCtx, "pcm-worklet");
      node.port.onmessage = (event) => sendPcm(event.data);
      source.connect(node);
      node.connect(mute);
      sent = true;
    } catch (e) {
      sent = false;
    }
  }
  if (!sent) {
    const processor = state.audioCtx.createScriptProcessor(2048, 1, 1);
    processor.onaudioprocess = (event) => {
      const input = event.inputBuffer.getChannelData(0);
      const pcm = new Int16Array(input.length);
      for (let i = 0; i < input.length; i++) {
        let sample = input[i];
        if (sample > 1) sample = 1;
        else if (sample < -1) sample = -1;
        pcm[i] = sample < 0 ? sample * 32768 : sample * 32767;
      }
      sendPcm(pcm);
    };
    source.connect(processor);
    processor.connect(mute);
  }
  mute.connect(state.audioCtx.destination);
  if (!state.meterOn) {
    state.meterOn = true;
    meter();
  }
}

function meter() {
  if (!state.want || !state.analyser) {
    state.meterOn = false;
    return;
  }
  const data = new Uint8Array(state.analyser.fftSize);
  state.analyser.getByteTimeDomainData(data);
  let peak = 0;
  for (let i = 0; i < data.length; i++) {
    const v = Math.abs(data[i] - 128) / 128;
    if (v > peak) peak = v;
  }
  levelEl.style.width = `${Math.min(100, Math.round(peak * 140))}%`;
  requestAnimationFrame(meter);
}

function sendPcm(pcm) {
  if (!state.ready || !state.ws || state.ws.readyState !== WebSocket.OPEN) return;
  if (state.ws.bufferedAmount > 400000) return;
  const payload = new Uint8Array(9 + pcm.byteLength);
  const view = new DataView(payload.buffer);
  payload[0] = 2;
  view.setUint32(1, state.audioCtx ? state.audioCtx.sampleRate : 48000, true);
  view.setUint16(5, 1, true);
  view.setUint16(7, pcm.length, true);
  payload.set(new Uint8Array(pcm.buffer, pcm.byteOffset, pcm.byteLength), 9);
  state.ws.send(payload);
  state.bytes += payload.length;
}

function sendVideo(bytes, width, height) {
  if (!state.ready || !state.ws || state.ws.readyState !== WebSocket.OPEN) return;
  if (state.ws.bufferedAmount > 1500000) return;
  const payload = new Uint8Array(5 + bytes.length);
  const view = new DataView(payload.buffer);
  payload[0] = 1;
  view.setUint16(1, width, true);
  view.setUint16(3, height, true);
  payload.set(bytes, 5);
  state.ws.send(payload);
  state.bytes += payload.length;
  state.frames += 1;
  const now = performance.now();
  if (!state.statAt) state.statAt = now;
  if (now - state.statAt > 1000) {
    const seconds = (now - state.statAt) / 1000;
    const kbps = Math.round((state.bytes * 8) / seconds / 1000);
    statsEl.textContent = `${width}×${height} · ${Math.round(state.frames / seconds)} fps · ${kbps} kbps`;
    state.bytes = 0;
    state.frames = 0;
    state.statAt = now;
  }
}

function drawFrame() {
  const chosen = preset();
  const canvas = state.canvas;
  if (canvas.width !== chosen.w || canvas.height !== chosen.h) {
    canvas.width = chosen.w;
    canvas.height = chosen.h;
  }
  const ctx = state.ctx2d;
  const vw = preview.videoWidth || chosen.w;
  const vh = preview.videoHeight || chosen.h;
  const scale = Math.max(chosen.w / vw, chosen.h / vh);
  const dw = vw * scale;
  const dh = vh * scale;
  const dx = (chosen.w - dw) / 2;
  const dy = (chosen.h - dh) / 2;
  ctx.fillStyle = "#000";
  ctx.fillRect(0, 0, chosen.w, chosen.h);
  ctx.save();
  if (mirrorInput.checked) {
    ctx.translate(chosen.w, 0);
    ctx.scale(-1, 1);
  }
  ctx.drawImage(preview, dx, dy, dw, dh);
  ctx.restore();
}

function videoLoop() {
  if (!state.want) return;
  state.timer = setTimeout(videoLoop, 1000 / preset().fps);
  const track = state.stream && state.stream.getVideoTracks()[0];
  if (!track || state.busy || preview.readyState < 2) return;
  state.busy = true;
  drawFrame();
  state.canvas.toBlob(async (blob) => {
    try {
      if (blob && state.want) {
        const bytes = new Uint8Array(await blob.arrayBuffer());
        sendVideo(bytes, state.canvas.width, state.canvas.height);
      }
    } finally {
      state.busy = false;
    }
  }, "image/jpeg", preset().q);
}

function wsEndpoint() {
  const cfg = window.NEXOCAM || {};
  if (cfg.wsUrl) return cfg.wsUrl;
  const proto = location.protocol === "https:" ? "wss" : "ws";
  return `${proto}://${location.host}/ws`;
}

function connectWs() {
  if (!state.want) return;
  const endpoint = wsEndpoint();
  if (!endpoint) {
    setStatus(t("noWs"));
    state.want = false;
    cleanup();
    return;
  }
  const url = `${endpoint}?room=${encodeURIComponent(roomCode())}&role=client`;
  const ws = new WebSocket(url);
  ws.binaryType = "arraybuffer";
  state.ws = ws;
  state.ready = false;
  ws.onopen = () => {
    ws.send(JSON.stringify({
      type: "auth",
      role: "client",
      room: roomCode(),
      password: passwordValue(),
    }));
  };
  ws.onmessage = (event) => {
    if (typeof event.data !== "string") return;
    let msg;
    try { msg = JSON.parse(event.data); } catch (e) { return; }
    if (msg.type === "welcome") {
      state.ready = true;
      const cam = cameraSelect.selectedOptions[0];
      const mic = micSelect.selectedOptions[0];
      const name = nameInput.value.trim() || defaultName();
      const detail = [name, cam && cam.value !== "__none__" ? cam.textContent : "", mic && mic.value !== "__none__" ? mic.textContent : ""]
        .filter(Boolean)
        .join(" · ");
      ws.send(JSON.stringify({ type: "hello", name: detail }));
      setStatus(t("live"));
    } else if (msg.type === "error") {
      setStatus(t("badRoom"));
      state.want = false;
      cleanup();
    }
  };
  ws.onclose = () => {
    state.ready = false;
    if (state.want && state.ws === ws) {
      setStatus(t("reconnecting"));
      setTimeout(connectWs, 1200);
    }
  };
}

async function lockWake() {
  try {
    if (navigator.wakeLock)
      state.wakeLock = await navigator.wakeLock.request("screen");
  } catch (e) {
    state.wakeLock = null;
  }
}

function cleanup() {
  clearTimeout(state.timer);
  state.ready = false;
  if (state.ws) {
    state.ws.onclose = null;
    state.ws.close();
    state.ws = null;
  }
  stopTracks();
  if (state.audioCtx) {
    state.audioCtx.close().catch(() => {});
    state.audioCtx = null;
  }
  state.analyser = null;
  levelEl.style.width = "0%";
  if (state.wakeLock) {
    state.wakeLock.release().catch(() => {});
    state.wakeLock = null;
  }
  $("setup").hidden = false;
  $("live").hidden = true;
}

async function start() {
  const code = roomCode();
  roomInput.value = code;
  if (code.length < 4) {
    setStatus(t("needRoom"));
    return;
  }
  if (!passwordValue()) {
    setStatus(t("needPassword"));
    return;
  }
  if (cameraSelect.value === "__none__" && micSelect.value === "__none__") {
    setStatus(t("needDevice"));
    return;
  }
  state.want = true;
  setStatus(t("starting"));
  try {
    if (micSelect.value !== "__none__") {
      try { state.audioCtx = new AudioContext({ sampleRate: 48000 }); }
      catch (e) { state.audioCtx = new AudioContext(); }
    }
    await openMedia();
  } catch (error) {
    state.want = false;
    cleanup();
    setStatus(error && error.message === "need-device" ? t("needDevice") : mediaError(error));
    return;
  }
  $("setup").hidden = true;
  $("live").hidden = false;
  preview.classList.toggle("mirror", mirrorInput.checked);
  await lockWake();
  connectWs();
  if (state.stream && state.stream.getVideoTracks().length)
    videoLoop();
}

function stop() {
  state.want = false;
  cleanup();
  setStatus(t("stopped"));
}

async function pickFacing(mode) {
  mirrorInput.checked = mode === "user";
  if (state.want) {
    if (state.audioCtx) {
      const old = state.audioCtx;
      state.audioCtx = null;
      old.close().catch(() => {});
    }
    if (micSelect.value !== "__none__") {
      try { state.audioCtx = new AudioContext({ sampleRate: 48000 }); }
      catch (e) { state.audioCtx = new AudioContext(); }
    }
  }
  try {
    const stream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: { ideal: mode } },
      audio: false,
    });
    const id = stream.getVideoTracks()[0].getSettings().deviceId;
    stream.getTracks().forEach((track) => track.stop());
    await refreshDevices();
    if (id && [...cameraSelect.options].some((option) => option.value === id))
      cameraSelect.value = id;
    if (state.want) await openMedia();
  } catch (error) {
    setStatus(mediaError(error));
  }
}

function boot() {
  const saved = localStorage.getItem("nexocam-lang");
  if (saved === "en" || saved === "es") state.lang = saved;
  else if ((navigator.language || "").toLowerCase().startsWith("en")) state.lang = "en";
  const params = new URLSearchParams(location.search);
  const room = (params.get("room") || "").toUpperCase();
  if (room) roomInput.value = room;
  nameInput.value = defaultName();
  state.ctx2d = state.canvas.getContext("2d", { alpha: false });
  applyLang();
  refreshDevices();
}

$("lang-es").onclick = () => {
  state.lang = "es";
  localStorage.setItem("nexocam-lang", "es");
  applyLang();
};
$("lang-en").onclick = () => {
  state.lang = "en";
  localStorage.setItem("nexocam-lang", "en");
  applyLang();
};
$("start").onclick = () => start();
$("stop").onclick = () => stop();
$("refresh").onclick = () => refreshDevices();
$("facing-back").onclick = () => pickFacing("environment");
$("facing-front").onclick = () => pickFacing("user");
mirrorInput.onchange = () => preview.classList.toggle("mirror", mirrorInput.checked);
window.addEventListener("pagehide", () => stop());
if (navigator.mediaDevices) navigator.mediaDevices.addEventListener("devicechange", () => refreshDevices());

boot();


