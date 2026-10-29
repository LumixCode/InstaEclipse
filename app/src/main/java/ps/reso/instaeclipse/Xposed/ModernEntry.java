package ps.reso.instaeclipse.Xposed;

import android.content.pm.ApplicationInfo;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedModule;
import ps.reso.instaeclipse.xposedcompat.ModernBackend;
import ps.reso.instaeclipse.xposedcompat.XC_LoadPackage;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

public class ModernEntry extends XposedModule {

    private final Module module = new Module();
    private String processName;

    public ModernEntry() {
        super();
    }

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        processName = param.getProcessName();
        XposedBridge.setBackend(new ModernBackend(this));
        ApplicationInfo self = getModuleApplicationInfo();
        Module.setModulePaths(self.sourceDir, self.nativeLibraryDir);
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        XC_LoadPackage.LoadPackageParam p = new XC_LoadPackage.LoadPackageParam();
        p.packageName = param.getPackageName();
        p.processName = processName;
        p.classLoader = param.getClassLoader();
        p.appInfo = param.getApplicationInfo();
        module.handleLoadPackage(p);
    }
}
