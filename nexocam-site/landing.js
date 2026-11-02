const STR = {
  es: {
    headline: "Cualquier cámara. OBS. Un nexo.",
    lead: "Conecta el teléfono, tablet u otro PC, o descarga el plugin para OBS.",
    connect: "Conectar",
    download: "Descargar plugin",
    privacy: "Privacidad",
  },
  en: {
    headline: "Any camera. OBS. One link.",
    lead: "Connect a phone, tablet, or another PC — or download the OBS plugin.",
    connect: "Connect",
    download: "Download plugin",
    privacy: "Privacy",
  },
};

const cfg = window.NEXOCAM || {};
let lang = "es";

function apply() {
  const t = STR[lang] || STR.es;
  document.documentElement.lang = lang;
  document.getElementById("brand").textContent = cfg.brand || "NexoCam";
  document.getElementById("headline").textContent = t.headline;
  document.getElementById("lead").textContent = t.lead;
  const connect = document.getElementById("connect");
  connect.textContent = t.connect;
  connect.href = cfg.appUrl || "/app/";
  const download = document.getElementById("download");
  download.textContent = t.download;
  download.href = cfg.downloadUrl || cfg.githubUrl || "#";
  document.getElementById("github").href = cfg.githubUrl || "#";
  document.getElementById("privacy").textContent = t.privacy;
  document.getElementById("lang-es").classList.toggle("active", lang === "es");
  document.getElementById("lang-en").classList.toggle("active", lang === "en");
}

document.getElementById("lang-es").onclick = () => {
  lang = "es";
  localStorage.setItem("nexocam-lang", "es");
  apply();
};
document.getElementById("lang-en").onclick = () => {
  lang = "en";
  localStorage.setItem("nexocam-lang", "en");
  apply();
};

const saved = localStorage.getItem("nexocam-lang");
if (saved === "en" || saved === "es") lang = saved;
else if ((navigator.language || "").toLowerCase().startsWith("en")) lang = "en";
apply();
