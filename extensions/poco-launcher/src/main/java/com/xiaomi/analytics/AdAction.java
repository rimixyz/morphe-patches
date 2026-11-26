package com.xiaomi.analytics;

@SuppressWarnings("unused")
public class AdAction extends Action {
    @Override
    public Action addParam(String key, long value) {
        return this;
    }

    @Override
    public Action addParam(String key, String value) {
        return this;
    }
}
