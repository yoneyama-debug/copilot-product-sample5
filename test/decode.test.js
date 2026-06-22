const test = require("node:test");
const assert = require("node:assert/strict");
const { decodeDemoData } = require("../server");

function build75ByteTempHumidityData() {
  return "0141c800000242480000".repeat(7) + "0141c80000";
}

test("decodeDemoData: 温度のみ", () => {
  const decoded = decodeDemoData("0141c80000");
  assert.equal(decoded.temperature, 25);
  assert.equal(decoded.humidity, null);
});

test("decodeDemoData: 湿度のみ", () => {
  const decoded = decodeDemoData("0242480000");
  assert.equal(decoded.temperature, null);
  assert.equal(decoded.humidity, 50);
});

test("decodeDemoData: 温度+湿度連結", () => {
  const decoded = decodeDemoData("0141c800000242480000");
  assert.equal(decoded.temperature, 25);
  assert.equal(decoded.humidity, 50);
});

test("decodeDemoData: float32不足は失敗", () => {
  const decoded = decodeDemoData("0141c8");
  assert.equal(decoded.temperature, null);
  assert.equal(decoded.humidity, null);
});

test("decodeDemoData: 任意データは表示値null", () => {
  const decoded = decodeDemoData("00ff");
  assert.equal(decoded.temperature, null);
  assert.equal(decoded.humidity, null);
});

test("decodeDemoData: 75byte温湿度データをデコードできる", () => {
  const decoded = decodeDemoData(build75ByteTempHumidityData());
  assert.equal(decoded.temperature, 25);
  assert.equal(decoded.humidity, 50);
  assert.equal(decoded.decodedItems.length, 15);
});

test("decodeDemoData: 77byteで任意データ含有なら表示値null", () => {
  const decoded = decodeDemoData(`${build75ByteTempHumidityData()}00ff`);
  assert.equal(decoded.temperature, null);
  assert.equal(decoded.humidity, null);
});

test("decodeDemoData: 末尾未完データは失敗", () => {
  const decoded = decodeDemoData(`${build75ByteTempHumidityData()}01`);
  assert.equal(decoded.temperature, null);
  assert.equal(decoded.humidity, null);
});
