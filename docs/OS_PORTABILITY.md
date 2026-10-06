# OS PORTABILITY (Faza 9 ulaz — živi dokument, dopunjava se svakom fazom)

Sve što je u APK v1 zaobilaženo zbog platformskih ograničenja, kao ulaz za AgentMujoGMS OS
(gdje imamo radio drajvere, nema Doze/OEM ubica, nema permission dijaloga).

## Mrežni sloj
1. Nema ad-hoc/IBSS/802.11s ni monitor mode → topologija "BLE discovery + Wi-Fi bulk", ne pravi mesh radio.
   OS: 802.11s ili custom MAC s pravim broadcastom.
2. Wi-Fi Direct traži korisnički dijalog + gasi normalni Wi-Fi na nekim uređajima → fallback LAN/hotspot.
   OS: direktna kontrola interfejsa.
3. BLE MTU 20B default, pregovori oportunistički → frameovi se drobe na sitno; GATT je spor za bulk.
   OS: veći MTU / drugi PHY.
4. Broj istovremenih veza ograničen (Direct/Aware/BLE različito po OEM-u) → mreža je rijetka po dizajnu.
5. Pozadina: foreground servis + notifikacija + izuzeće od baterije; OEM-i svejedno ubijaju → Capability Probe
   to mjeri, a ne pretpostavlja. OS: sistemski daemon bez ograničenja.

## Kripto/platforma
6. Pure-JDK kripto (nema JNI) jer je prenosivo; Android Conscrypt API nivoi još neprovjereni (TODO Faza 4-device).
   OS: isti kod radi nepromijenjen (JVM) ili libsodium direktno.
7. `local.properties`/SDK samo lokalno; toolchain reproducibilan (docs/ANDROID_TOOLCHAIN.md).

## Servisi (ograničenja v1)
8. Grupni chat = broadcast + app filter (nema mesh group-dst optimizacije).
9. E2E za grupe ne postoji (samo 1-1 unicast box).
10. Fajlovi: chunk 1KB, manifest-push model; nema DHT ni erasure codinga.
11. SOS plaintext po dizajnu; metapodaci vidljivi (nema onion routinga).
12. Reboot gubi seq/seen (nema perzistencije) → boot-epoch TODO.

## Šta se prenosi nepromijenjeno u OS
`core-mesh`, `transport-api` (ugovor), `service-chat`, `service-files`, `agent`, kripto kompozicija.
Mijenja se samo: transport implementacije + Probe čitač + foreground servis → sistemski servisi.
