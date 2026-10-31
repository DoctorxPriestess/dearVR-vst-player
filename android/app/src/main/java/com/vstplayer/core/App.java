package com.vstplayer.core;

import android.app.Application;

public class App {
    private static Env env;
    private static Engine engine;

    public static synchronized Env env(android.content.Context ctx) {
        if (env == null) env = new Env(ctx);
        return env;
    }

    public static synchronized Engine engine(android.content.Context ctx) {
        if (engine == null) engine = new Engine(env(ctx));
        return engine;
    }
}
