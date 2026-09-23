pluginManagement {
    repositories {
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        maven("https://mirrors.huaweicloud.com/repository/maven/")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        maven("https://mirrors.huaweicloud.com/repository/maven/")
    }
}

rootProject.name = "WearMusic"

include(":app")
include(":core:netease")
