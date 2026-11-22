package com.sesame.app.shizuku;

oneway interface IGhostlockCallback {
    void onLog(String line);
    void onComplete(int exitCode);
}
