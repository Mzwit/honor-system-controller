package com.honor.systemcontroller

import android.app.Application
import com.honor.systemcontroller.core.Settings
import com.honor.systemcontroller.core.automation.BlocklistEngine
import com.honor.systemcontroller.core.automation.BlocklistWorker
import com.honor.systemcontroller.core.backup.RecoveryManager
import com.honor.systemcontroller.core.packages.AppDiscovery
import com.honor.systemcontroller.core.packages.PackageController
import com.honor.systemcontroller.core.privilege.PrivilegeManager
import com.honor.systemcontroller.core.privilege.ShizukuState
import com.honor.systemcontroller.core.process.ProcessInspector
import com.honor.systemcontroller.data.AppDatabase

/** Manual dependency container – one instance of each module. */
class AppContainer(app: HonorControllerApp) {
    val settings = Settings(app)
    val db = AppDatabase.create(app)
    val privilege = PrivilegeManager(settings)
    val discovery = AppDiscovery(app)
    val controller = PackageController(app, db, privilege, discovery)
    val rootDebloat = com.honor.systemcontroller.core.packages.RootDebloat(db, privilege, discovery)
    val processes = ProcessInspector(app, privilege)
    val recovery = RecoveryManager(app, db)
    val security = com.honor.systemcontroller.core.security.SecurityInspector(app)
    val blocklist = BlocklistEngine(app)
}

class HonorControllerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        BlocklistWorker.createChannel(this)
        runCatching { ShizukuState.install() }
        if (container.settings.ruleVerifyBlocklist) BlocklistWorker.schedule(this, container.settings.verifyIntervalMin)
    }
}
