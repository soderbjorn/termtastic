// Thin Gradle wrapper around the Electron desktop shell.
// The shell itself is a plain npm project (package.json + main.js).
// This module just provides `:electron:run` and `:electron:dist` so the
// project can be driven through the existing ./gradlew workflow.

// :electron:run is wired to the dev server, which runs on 8083 (see
// server/build.gradle.kts). The packaged build still talks to SERVER_PORT
// (8082) — that path is handled inside main.js, not here.
val devServerPort = 8083
val targetUrl = "https://127.0.0.1:$devServerPort"

val nodeModulesDir = layout.projectDirectory.dir("node_modules")
val distDir = layout.projectDirectory.dir("dist")
val resourcesDir = layout.projectDirectory.dir("resources")

// Resolve npm via PATH so Gradle's subprocess can find it even when
// /opt/homebrew/bin isn't on the JVM's default search path.
val npmExec: String = System.getenv("PATH")
    ?.split(File.pathSeparator)
    ?.map { File(it, "npm") }
    ?.firstOrNull { it.canExecute() }
    ?.absolutePath
    ?: "npm"

val npmInstall by tasks.registering(Exec::class) {
    group = "electron"
    description = "Install Electron npm dependencies."
    workingDir = projectDir
    commandLine(npmExec, "install")
    inputs.file("package.json")
    outputs.dir(nodeModulesDir)
}

tasks.register<Exec>("run") {
    group = "electron"
    description = "Launch the Electron desktop shell pointing at the local server."
    dependsOn(npmInstall)
    workingDir = projectDir
    environment("TERMTASTIC_URL", targetUrl)
    commandLine(npmExec, "start")
}

// Stage the server fat jar inside the electron project so electron-builder
// picks it up via the `files` glob in package.json.
val copyServerJar by tasks.registering(Copy::class) {
    group = "electron"
    description = "Copy the server shadow jar into electron/resources/server.jar."
    val shadowJar = project(":server").tasks.named("shadowJar")
    dependsOn(shadowJar)
    from(shadowJar) {
        rename { "server.jar" }
    }
    into(resourcesDir)
}

tasks.register<Exec>("dist") {
    group = "electron"
    description = "Build a distributable Electron app via electron-builder."
    dependsOn(npmInstall, copyServerJar)
    workingDir = projectDir
    commandLine(npmExec, "run", "dist")
    inputs.file("package.json")
    inputs.file("main.js")
    inputs.file("preload.js")
    inputs.dir(resourcesDir)
    outputs.dir(distDir)
}

tasks.register<Delete>("clean") {
    group = "electron"
    description = "Remove node_modules, dist, and staged server resources."
    delete(nodeModulesDir, distDir, resourcesDir)
}
