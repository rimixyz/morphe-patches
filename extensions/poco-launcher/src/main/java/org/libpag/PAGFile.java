package org.libpag;

import android.content.res.AssetManager;

@SuppressWarnings("unused")
public class PAGFile extends PAGComposition {
    public interface LoadListener {
    }

    public static PAGFile Load(AssetManager manager, String fileName) {
        return null;
    }

    public static PAGFile Load(byte[] bytes) {
        return null;
    }

    public PAGText getTextData(int index) {
        return null;
    }

    public int numImages() {
        return 0;
    }

    public int numTexts() {
        return 0;
    }

    public String path() {
        return "";
    }

    public void replaceImage(int index, PAGImage image) {
    }

    public void replaceText(int index, PAGText textData) {
    }
}
