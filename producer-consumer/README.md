To debug this project locally, perform the followingcommands:
```
# in project root
./gradlew publishToMavenLocal
cd producer-consumer
./gradlew :producer:publishToMavenLocal
./gradlew :consumer:run
```

If you would like to debug the compilation process (this was useful for me to debug serialization/deserialization), following these steps:
1. download the Kotlin compiler
2. publish the Kotlin compiler locally
3. update the version of the compiler used in this repo by editing the gradle.properties file.
4. Remote debug from this repo by using this command: ```./gradlew [task] --no-daemon -Dorg.gradle.debug=true -Dkotlin.compiler.execution.strategy="in-process" -Dkotlin.daemon.jvm.options="-Xdebug,-Xrunjdwp:transport=dt_socket,address=5005,server=y,suspend=n"```, where task refers to either consumer:run or producer:compileKotlin.
5. Setup a Remote JVM Debug build in the compiler and execute it