package com.example.touristguide;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

/** Initialises local storage and the chosen theme before any screen is created. */
public class TouristApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Store.init(this);
        AppCompatDelegate.setDefaultNightMode(Store.themeMode());
    }
}
