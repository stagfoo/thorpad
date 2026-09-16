package com.thorpad.app;

interface IStickCallback {
    // oneway: the reader must never block waiting for the app to draw a frame.
    oneway void onStick(float x, float y) = 1;
    oneway void onFailed(String why) = 2;
}
