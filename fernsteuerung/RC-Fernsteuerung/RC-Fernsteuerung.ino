/*
  RC-Fernsteuerung per Handy (2,4 GHz WLAN)
  -----------------------------------------
  Der ESP32 sitzt im Modell und ersetzt den RC-Empfänger:
   - er öffnet ein eigenes WLAN (Access Point),
   - liefert unter http://192.168.4.1 die Steuer-Webseite aus,
   - empfängt die Stickwerte per WebSocket (50 x pro Sekunde),
   - gibt sie als normales Servo-Signal (1000–2000 µs, 50 Hz) an Servos und Fahrtregler aus,
   - schaltet auf Failsafe, wenn länger als FAILSAFE_MS nichts mehr ankommt.

  Benötigt:
   - Arduino IDE mit „esp32 by Espressif Systems“ (Boardverwalter, Version 2.x oder 3.x)
   - Bibliothek „WebSockets“ von Markus Sattler (Links2004) aus der Bibliotheksverwaltung
*/

#include <WiFi.h>
#include <WebServer.h>
#include <WebSocketsServer.h>
#include <esp_wifi.h>
#include <esp_arduino_version.h>
#include "webui.h"

// ===================== Einstellungen =====================

const char* WLAN_NAME     = "RC-Modell";
const char* WLAN_PASSWORT = "fahren1234";   // mindestens 8 Zeichen – bitte ändern!
const int   WLAN_KANAL    = 6;              // 1–13; bei Störungen anderen Kanal wählen

// Ausgänge für die 4 Kanäle (GPIO-Nummern, klassischer ESP32)
//   K1 = rechter Stick waagrecht  (Lenkung / Querruder)
//   K2 = linker Stick senkrecht   (Gas / Fahrtregler)
//   K3 = rechter Stick senkrecht  (Höhenruder / frei)
//   K4 = linker Stick waagrecht   (Seitenruder / frei)
const uint8_t KANAL_PINS[4] = {18, 19, 21, 22};

// Index des Gaskanals (0 = K1, 1 = K2 …). Gas bleibt auf Failsafe, solange
// in der App nicht „Scharf“ geschaltet ist.
const uint8_t GAS_KANAL = 1;

// Werte bei Verbindungsverlust (µs).
//   Auto/Boot mit Vor-/Rückwärts-Regler: Gas 1500 (= Stopp)
//   Flugzeug / Regler nur vorwärts:      Gas 1000 (= Motor aus)
const uint16_t FAILSAFE_US[4] = {1500, 1500, 1500, 1500};
const uint32_t FAILSAFE_MS    = 500;        // nach so vielen ms ohne Daten -> Failsafe

// Status-LED (leuchtet bei aktiver Steuerung, blinkt im Failsafe). -1 = keine
const int LED_PIN = 2;

// Optional: Akkuspannung messen (über Spannungsteiler an einen ADC-Pin, max. 3,3 V am Pin!)
// Beispiel 2S-LiPo (8,4 V): 10 kΩ / 4,7 kΩ -> AKKU_TEILER = (10 + 4.7) / 4.7 = 3.13
const int   AKKU_PIN    = -1;               // z. B. 34; -1 = aus
const float AKKU_TEILER = 3.13;

// =========================================================

WebServer        server(80);
WebSocketsServer ws(81);

int8_t   steuerer       = -1;     // WebSocket-Client, der gerade steuert
uint32_t letztesPaket   = 0;
bool     failsafeAktiv  = true;
uint32_t letzteTelemetrie = 0;

const uint8_t  PWM_BITS = 14;      // 14 Bit läuft auf allen ESP32-Varianten
const uint32_t PWM_HZ   = 50;

void pwmStart(uint8_t i) {
#if ESP_ARDUINO_VERSION_MAJOR >= 3
  ledcAttach(KANAL_PINS[i], PWM_HZ, PWM_BITS);
#else
  ledcSetup(i, PWM_HZ, PWM_BITS);
  ledcAttachPin(KANAL_PINS[i], i);
#endif
}

void schreibeKanal(uint8_t i, uint16_t us) {
  us = constrain(us, 800, 2200);
  uint32_t duty = (uint32_t)us * (1UL << PWM_BITS) / (1000000UL / PWM_HZ);
#if ESP_ARDUINO_VERSION_MAJOR >= 3
  ledcWrite(KANAL_PINS[i], duty);
#else
  ledcWrite(i, duty);
#endif
}

void failsafe() {
  for (uint8_t i = 0; i < 4; i++) schreibeKanal(i, FAILSAFE_US[i]);
  failsafeAktiv = true;
}

void sendeTelemetrie() {
  String json = "{";
  wifi_sta_list_t liste;
  if (esp_wifi_ap_get_sta_list(&liste) == ESP_OK && liste.num > 0) {
    int rssi = liste.sta[0].rssi;
    for (int i = 1; i < liste.num; i++) rssi = max(rssi, (int)liste.sta[i].rssi);
    json += "\"rssi\":" + String(rssi);
  } else {
    json += "\"rssi\":null";
  }
  if (AKKU_PIN >= 0) {
    float volt = analogReadMilliVolts(AKKU_PIN) * AKKU_TEILER / 1000.0;
    json += ",\"akku\":" + String(volt, 2);
  }
  json += ",\"fs\":" + String(failsafeAktiv ? 1 : 0) + "}";
  ws.broadcastTXT(json);
}

void wsEreignis(uint8_t num, WStype_t typ, uint8_t* daten, size_t laenge) {
  switch (typ) {
    case WStype_BIN: {
      // Paket: 4 x uint16 (µs, little endian) + 1 Byte Flags (Bit 0 = scharf)
      if (laenge != 9) return;
      // Ein zweites Handy darf erst übernehmen, wenn das erste schweigt
      if (steuerer >= 0 && num != steuerer && millis() - letztesPaket < FAILSAFE_MS) return;
      uint16_t us[4];
      for (uint8_t i = 0; i < 4; i++) {
        us[i] = daten[2 * i] | (daten[2 * i + 1] << 8);
        if (us[i] < 800 || us[i] > 2200) return;   // ungültiges Paket verwerfen
      }
      bool scharf = daten[8] & 0x01;
      if (!scharf) us[GAS_KANAL] = FAILSAFE_US[GAS_KANAL];
      for (uint8_t i = 0; i < 4; i++) schreibeKanal(i, us[i]);
      steuerer      = num;
      letztesPaket  = millis();
      failsafeAktiv = false;
      break;
    }
    case WStype_TEXT:
      // Ping für die Latenzanzeige: unverändert zurückschicken
      if (laenge > 0 && daten[0] == 'p') ws.sendTXT(num, daten, laenge);
      break;
    case WStype_DISCONNECTED:
      if (num == steuerer) {
        steuerer = -1;
        failsafe();
      }
      break;
    default:
      break;
  }
}

void setup() {
  Serial.begin(115200);

  // Ausgänge sofort auf sichere Werte
  for (uint8_t i = 0; i < 4; i++) pwmStart(i);
  failsafe();
  if (LED_PIN >= 0) pinMode(LED_PIN, OUTPUT);

  WiFi.mode(WIFI_AP);
  WiFi.softAP(WLAN_NAME, WLAN_PASSWORT, WLAN_KANAL, 0, 2);   // max. 2 Handys
  WiFi.setSleep(false);                                      // geringere Latenz

  server.on("/", []() {
    server.sendHeader("Cache-Control", "no-cache");
    server.send(200, "text/html", INDEX_HTML);
  });
  server.onNotFound([]() {
    server.sendHeader("Location", "http://192.168.4.1/");
    server.send(302, "text/plain", "");
  });
  server.begin();

  ws.begin();
  ws.onEvent(wsEreignis);
  ws.enableHeartbeat(1000, 1000, 2);   // tote Verbindungen nach ca. 3 s trennen

  Serial.printf("WLAN \"%s\" offen – im Handy-Browser http://%s öffnen\n",
                WLAN_NAME, WiFi.softAPIP().toString().c_str());
}

void loop() {
  ws.loop();
  server.handleClient();

  uint32_t jetzt = millis();
  if (!failsafeAktiv && jetzt - letztesPaket > FAILSAFE_MS) failsafe();

  if (jetzt - letzteTelemetrie >= 500) {
    letzteTelemetrie = jetzt;
    sendeTelemetrie();
  }

  if (LED_PIN >= 0) digitalWrite(LED_PIN, failsafeAktiv ? ((jetzt / 250) % 2) : HIGH);
}
