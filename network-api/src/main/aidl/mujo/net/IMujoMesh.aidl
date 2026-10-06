package mujo.net;

// Network API v1 (Faza 6a): AIDL granica između servisa i mrežnog stacka.
// Servisi NIKAD ne pričaju direktno s transportom — samo ovuda.
// Polling model (bez callbacka) namjerno: najmanji API koji radi; listeneri kasnije.
interface IMujoMesh {
    /** JSON status čvora (isto što i service-web /status). */
    String getStatus();
    /** Grupni chat broadcast. */
    void sendChat(String group, String text);
    /** SOS broadcast s lokacijom; vraća sosId hex. */
    String startSos(String text, double lat, double lon);
    /** Pokupi nove chat poruke (newline: group|text); konzumira. */
    String pollChats(String group);
}
