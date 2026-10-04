plugins {
    java
    application
    id("me.champeau.jmh") version "0.7.2"
}

group = "com.cloblab"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")

    jmh("org.openjdk.jmh:jmh-core:1.37")
    jmhAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("com.cloblab.bench.MatchBenchmark")
}

tasks.register<JavaExec>("runDemo") {
    group = "application"
    description = "Run interactive CLOB demo"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.cloblab.Main")
}

tasks.register<JavaExec>("runLoadTest") {
    group = "application"
    description = "Run CLOB load test (throughput + latency under sustained traffic)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.cloblab.loadtest.LoadTestRunner")
    args = listOf("--mode=single", "--duration=10")
}

tasks.register<JavaExec>("runPipeline") {
    group = "application"
    description = "Run cloud-exchange pipeline demo (sequencer, FancyPQ, fair MD)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.cloblab.pipeline.PipelineRunner")
}

jmh {
    jmhVersion.set("1.37")
    warmupIterations.set(2)
    iterations.set(3)
    fork.set(1)
    benchmarkMode.set(listOf("avgt"))
    timeUnit.set("ns")
    includes.set(listOf(".*Benchmark.*"))
    resultsFile.set(layout.buildDirectory.file("reports/jmh/results.txt"))
    humanOutputFile.set(layout.buildDirectory.file("reports/jmh/results.txt"))
}
