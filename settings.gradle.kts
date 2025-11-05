if (!System.getenv("NEXUS_PUBLIC_URL").isNullOrBlank()) {
    pluginManagement {
        repositories {
            maven {
                url = uri(System.getenv("NEXUS_PUBLIC_URL"))
            }
        }
    }
}
rootProject.name = "javelin"