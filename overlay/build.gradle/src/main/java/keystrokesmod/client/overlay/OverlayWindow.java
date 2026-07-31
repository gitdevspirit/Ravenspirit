apply plugin: 'java'

repositories {
    mavenCentral()
}

dependencies {
    // No external dependencies needed
}

jar {
    manifest {
        attributes(
            'Main-Class': 'keystrokesmod.client.overlay.OverlayWindow',
            'Implementation-Title': 'Raven ESP Overlay',
            'Implementation-Version': '1.0'
        )
    }
    
    // Include all dependencies in the JAR
    from {
        configurations.compile.collect { it.isDirectory() ? it : zipTree(it) }
    }
}
