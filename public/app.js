const HISTORY_MAX = 100;
const CHART_MAX = 50;
const MINI_MAX = 20;
const SSE_MAX_RETRY = 10;
const SSE_RETRY_MS = 3000;

const store = {
  history: [],
  tempBuffer: [],
  humBuffer: [],
  miniTempBuffer: [],
};

let tempChart = null;
let humChart = null;
let retryCount = 0;

const elements = {
  cardTemp: document.getElementById("card-temp"),
  cardHum: document.getElementById("card-hum"),
  tempValue: document.getElementById("temp-value"),
  humValue: document.getElementById("hum-value"),
  miniTempCanvas: document.getElementById("mini-chart-temp"),
  humGaugeCanvas: document.getElementById("gauge-hum"),
  tableBody: document.getElementById("table-body"),
  chartTempCanvas: document.getElementById("chart-temp"),
  chartHumCanvas: document.getElementById("chart-hum"),
  chartError: document.getElementById("chart-error"),
};

function formatValue(value) {
  return Number.isFinite(value) ? value.toFixed(3) : "--";
}

function extractTime(timestamp) {
  if (typeof timestamp !== "string" || timestamp.length < 19) {
    return "--:--:--";
  }
  return timestamp.substring(11, 19);
}

function pushLimited(array, item, max) {
  array.push(item);
  if (array.length > max) {
    array.shift();
  }
}

function updateCards(event) {
  elements.tempValue.textContent = formatValue(event.temperature);
  elements.humValue.textContent = formatValue(event.humidity);
}

function drawMiniTempChart() {
  const canvas = elements.miniTempCanvas;
  const ctx = canvas.getContext("2d");
  const width = canvas.width;
  const height = canvas.height;
  ctx.clearRect(0, 0, width, height);

  if (store.miniTempBuffer.length < 2) {
    return;
  }

  let min = Number.POSITIVE_INFINITY;
  let max = Number.NEGATIVE_INFINITY;
  for (const point of store.miniTempBuffer) {
    if (point.y < min) min = point.y;
    if (point.y > max) max = point.y;
  }

  const range = Math.max(max - min, 0.001);
  const left = 8;
  const right = width - 8;
  const top = 8;
  const bottom = height - 8;

  ctx.strokeStyle = "#00d4ff";
  ctx.lineWidth = 2;
  ctx.beginPath();

  store.miniTempBuffer.forEach((point, index) => {
    const x = left + (index * (right - left)) / (store.miniTempBuffer.length - 1);
    const y = bottom - ((point.y - min) / range) * (bottom - top);
    if (index === 0) {
      ctx.moveTo(x, y);
    } else {
      ctx.lineTo(x, y);
    }
  });

  ctx.stroke();
}

function drawHumidityGauge() {
  const canvas = elements.humGaugeCanvas;
  const ctx = canvas.getContext("2d");
  const width = canvas.width;
  const height = canvas.height;
  const centerX = width / 2;
  const centerY = height * 0.9;
  const radius = Math.min(width * 0.38, height * 0.7);
  const start = Math.PI;
  const end = 2 * Math.PI;

  const latest = store.history[0];
  const humidity = Number.isFinite(latest?.humidity) ? latest.humidity : null;
  const ratio = humidity === null ? 0 : Math.min(Math.max(humidity, 0), 100) / 100;

  ctx.clearRect(0, 0, width, height);

  ctx.lineWidth = 10;
  ctx.strokeStyle = "rgba(255,255,255,0.15)";
  ctx.beginPath();
  ctx.arc(centerX, centerY, radius, start, end);
  ctx.stroke();

  ctx.strokeStyle = "#00d4ff";
  ctx.beginPath();
  ctx.arc(centerX, centerY, radius, start, start + (end - start) * ratio);
  ctx.stroke();
}

function buildMainChart(ctx, label, color, padding) {
  return new Chart(ctx, {
    type: "line",
    data: {
      labels: [],
      datasets: [
        {
          label,
          data: [],
          borderColor: color,
          backgroundColor: "transparent",
          pointRadius: 3,
          pointBackgroundColor: color,
          tension: 0.25,
        },
      ],
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      animation: false,
      plugins: {
        legend: {
          labels: { color: "#f8f8ff" },
        },
      },
      scales: {
        x: {
          ticks: { color: "#f8f8ff" },
          grid: { color: "rgba(255,255,255,0.1)" },
        },
        y: {
          ticks: { color: "#f8f8ff" },
          grid: { color: "rgba(255,255,255,0.1)" },
          afterDataLimits(axis) {
            axis.min -= padding;
            axis.max += padding;
          },
        },
      },
    },
  });
}

function setChartError(message) {
  elements.chartError.hidden = false;
  elements.chartError.textContent = message;
}

function initCharts() {
  if (typeof window.Chart === "undefined") {
    setChartError("Chart の読み込みに失敗しました。カードとテーブル更新は継続します。");
    return;
  }

  try {
    tempChart = buildMainChart(elements.chartTempCanvas.getContext("2d"), "Temperature", "#ff8c42", 0.5);
    humChart = buildMainChart(elements.chartHumCanvas.getContext("2d"), "Humidity", "#00d4ff", 1);
  } catch {
    tempChart = null;
    humChart = null;
    setChartError("グラフ初期化に失敗しました。カードとテーブル更新は継続します。");
  }
}

function updateCharts() {
  if (tempChart) {
    tempChart.data.labels = store.tempBuffer.map((point) => point.x);
    tempChart.data.datasets[0].data = store.tempBuffer.map((point) => point.y);
    tempChart.update("none");
  }

  if (humChart) {
    humChart.data.labels = store.humBuffer.map((point) => point.x);
    humChart.data.datasets[0].data = store.humBuffer.map((point) => point.y);
    humChart.update("none");
  }
}

function prependTableRow(event) {
  const row = document.createElement("tr");

  const ts = document.createElement("td");
  ts.textContent = String(event.timestamp ?? "");
  row.appendChild(ts);

  const data = document.createElement("td");
  data.textContent = String(event.data ?? "");
  row.appendChild(data);

  const size = document.createElement("td");
  size.textContent = `${event.dataSize ?? ""} byte`;
  row.appendChild(size);

  elements.tableBody.insertBefore(row, elements.tableBody.firstChild);
  while (elements.tableBody.children.length > HISTORY_MAX) {
    elements.tableBody.removeChild(elements.tableBody.lastChild);
  }
}

function addEvent(input) {
  if (!input || typeof input !== "object") {
    return;
  }

  const event = {
    timestamp: typeof input.timestamp === "string" ? input.timestamp : "",
    data: typeof input.data === "string" ? input.data : "",
    dataSize: Number.isInteger(input.dataSize) ? input.dataSize : 0,
    temperature: Number.isFinite(input.temperature) ? input.temperature : null,
    humidity: Number.isFinite(input.humidity) ? input.humidity : null,
  };

  store.history.unshift(event);
  if (store.history.length > HISTORY_MAX) {
    store.history.pop();
  }

  const x = extractTime(event.timestamp);
  if (event.temperature !== null) {
    pushLimited(store.tempBuffer, { x, y: event.temperature }, CHART_MAX);
    pushLimited(store.miniTempBuffer, { x, y: event.temperature }, MINI_MAX);
  }
  if (event.humidity !== null) {
    pushLimited(store.humBuffer, { x, y: event.humidity }, CHART_MAX);
  }

  updateCards(event);
  drawMiniTempChart();
  drawHumidityGauge();
  updateCharts();
  prependTableRow(event);
}

function selectCard(type) {
  const isTemp = type === "temp";

  elements.cardTemp.classList.toggle("selected-temp", isTemp);
  elements.cardHum.classList.toggle("selected-hum", !isTemp);

  elements.cardTemp.setAttribute("aria-pressed", String(isTemp));
  elements.cardHum.setAttribute("aria-pressed", String(!isTemp));

  elements.chartTempCanvas.style.display = isTemp ? "block" : "none";
  elements.chartHumCanvas.style.display = isTemp ? "none" : "block";
}

function registerCardInteractions() {
  const onKey = (type) => (event) => {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      selectCard(type);
    }
  };

  elements.cardTemp.addEventListener("click", () => selectCard("temp"));
  elements.cardHum.addEventListener("click", () => selectCard("hum"));
  elements.cardTemp.addEventListener("keydown", onKey("temp"));
  elements.cardHum.addEventListener("keydown", onKey("hum"));
}

async function loadHistory() {
  try {
    const response = await fetch("/api/history", {
      headers: { Accept: "application/json" },
    });
    if (!response.ok) {
      return;
    }

    const events = await response.json();
    if (!Array.isArray(events)) {
      return;
    }

    for (let index = events.length - 1; index >= 0; index -= 1) {
      addEvent(events[index]);
    }
  } catch {
  }
}

function connectSSE() {
  let eventSource;
  try {
    eventSource = new EventSource("/events");
  } catch {
    setChartError("SSE 接続に失敗しました。再接続を試行します。");
    return;
  }

  eventSource.addEventListener("sensorData", (event) => {
    retryCount = 0;
    try {
      const parsed = JSON.parse(event.data);
      addEvent(parsed);
    } catch {
    }
  });

  eventSource.onerror = () => {
    eventSource.close();

    if (retryCount < SSE_MAX_RETRY) {
      retryCount += 1;
      setTimeout(connectSSE, SSE_RETRY_MS);
      return;
    }

    setChartError("SSE 再接続回数の上限に達しました。");
  };
}

window.addEventListener("DOMContentLoaded", async () => {
  initCharts();
  registerCardInteractions();
  selectCard("temp");
  drawMiniTempChart();
  drawHumidityGauge();
  await loadHistory();
  connectSSE();
});
