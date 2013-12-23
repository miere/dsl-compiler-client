version in ThisBuild := "0.8.9"

organization in ThisBuild := "com.dslplatform"

val NGSNexus            = "NGS Nexus"             at "http://ngs.hr/nexus/content/groups/public/"
val NGSReleases         = "NGS Releases"          at "http://ngs.hr/nexus/content/repositories/releases/"
val NGSSnapshots        = "NGS Snapshots"         at "http://ngs.hr/nexus/content/repositories/snapshots/"
val NGSPrivateReleases  = "NGS Private Releases"  at "http://ngs.hr/nexus/content/repositories/releases-private/"
val NGSPrivateSnapshots = "NGS Private Snapshots" at "http://ngs.hr/nexus/content/repositories/snapshots-private/"

resolvers in ThisBuild := Seq(NGSNexus, NGSSnapshots, NGSPrivateReleases, NGSPrivateSnapshots)

externalResolvers in ThisBuild := Resolver.withDefaultResolvers(resolvers.value, mavenCentral = false)

publishTo in ThisBuild := Some(
  if (version.value endsWith "-PRIVATE-SNAPSHOT") NGSPrivateSnapshots else
  if (version.value endsWith "-PRIVATE") NGSPrivateReleases else
  if (version.value endsWith "-SNAPSHOT") NGSSnapshots else NGSReleases
)

credentials in ThisBuild += Credentials(Path.userHome / ".config" / "ngs-util_production" / "nexus.config")