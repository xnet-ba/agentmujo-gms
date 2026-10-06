plugins { kotlin("jvm") version "2.0.21"; application }
dependencies { implementation(project(":core-mesh")) }
application { mainClass.set("mujo.sim.MainKt") }
