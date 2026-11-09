pluginManagement {
    repositories {
        maven("http://nexus.vc.in/repository/hu_base_components_Snapshots") {
            setUrl("http://nexus.vc.in/repository/hu_base_components_Snapshots")
            isAllowInsecureProtocol = true
            credentials {
                username = "fengke"
                password = "Fk276551290!"
            }
        }
        maven("http://nexus.vc.in/repository/gradle-dev") {
            isAllowInsecureProtocol = true
        }
//        mavenCentral()
//        gradlePluginPortal()
//        google {
//            content {
//                includeGroupByRegex("com\\.android.*")
//                includeGroupByRegex("com\\.google.*")
//                includeGroupByRegex("androidx.*")
//            }
//        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("http://nexus.vc.in/repository/gradle-dev") {
            isAllowInsecureProtocol = true
            credentials {
                username = "<nexus user>"
                password = "<nexus password>"
            }
        }
        maven("http://nexus.vc.in/repository/hu_base_components_Snapshots") {
            isAllowInsecureProtocol = true
            credentials {
                username = "fengke"
                password = "Fk276551290!"
            }
        }
        maven("http://nexus.vc.in/repository/ftb3") {
            isAllowInsecureProtocol = true
        }
//        mavenCentral()
//        google()
    }
}

rootProject.name = "Plr"
include(":app")
 