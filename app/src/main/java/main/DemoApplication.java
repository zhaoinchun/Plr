package main;

import android.app.Application;
import android.os.StrictMode;

/** Enables strict mode penalties for the demo app. */
public class DemoApplication extends Application {

  @Override
  public void onCreate() {
    super.onCreate();
    StrictMode.setThreadPolicy(
        new StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build());
    StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build());
  }
}
