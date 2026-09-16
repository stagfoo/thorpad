package com.thorpad.app;

import com.thorpad.app.IStickCallback;

interface IStickService {
    String start(in IStickCallback callback, int stick) = 1;
    void stop() = 2;
    String describe() = 3;
    void destroy() = 16777114;
}
