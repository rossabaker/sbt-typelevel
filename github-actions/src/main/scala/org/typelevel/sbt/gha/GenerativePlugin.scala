/*
 * Copyright 2022 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.typelevel.sbt.gha

import sbt.Keys._
import sbt._

import java.nio.file.FileSystems
import scala.io.Source

object GenerativePlugin extends AutoPlugin {

  override def requires = plugins.JvmPlugin
  override def trigger = allRequirements

  object autoImport extends GenerativeKeys {
    type WorkflowJob = org.typelevel.sbt.gha.WorkflowJob
    val WorkflowJob = org.typelevel.sbt.gha.WorkflowJob

    type Concurrency = org.typelevel.sbt.gha.Concurrency
    val Concurrency = org.typelevel.sbt.gha.Concurrency

    type JobContainer = org.typelevel.sbt.gha.JobContainer
    val JobContainer = org.typelevel.sbt.gha.JobContainer

    type WorkflowStep = org.typelevel.sbt.gha.WorkflowStep
    val WorkflowStep = org.typelevel.sbt.gha.WorkflowStep

    type RefPredicate = org.typelevel.sbt.gha.RefPredicate
    val RefPredicate = org.typelevel.sbt.gha.RefPredicate

    type Ref = org.typelevel.sbt.gha.Ref
    val Ref = org.typelevel.sbt.gha.Ref

    type UseRef = org.typelevel.sbt.gha.UseRef
    val UseRef = org.typelevel.sbt.gha.UseRef

    type PREventType = org.typelevel.sbt.gha.PREventType
    val PREventType = org.typelevel.sbt.gha.PREventType

    type MatrixInclude = org.typelevel.sbt.gha.MatrixInclude
    val MatrixInclude = org.typelevel.sbt.gha.MatrixInclude

    type MatrixExclude = org.typelevel.sbt.gha.MatrixExclude
    val MatrixExclude = org.typelevel.sbt.gha.MatrixExclude

    type Paths = org.typelevel.sbt.gha.Paths
    val Paths = org.typelevel.sbt.gha.Paths

    type JavaSpec = org.typelevel.sbt.gha.JavaSpec
    val JavaSpec = org.typelevel.sbt.gha.JavaSpec
  }

  import autoImport._

  private object MatrixKeys {
    final val OS = "os"
    final val Scala = "scala"
    final val Java = "java"

    def groupId(keys: List[String]): String =
      (MatrixKeys.OS :: MatrixKeys.Java :: MatrixKeys.Scala :: keys)
        .map(k => s"$${{ matrix.$k }}")
        .mkString("-")
  }

  private def indent(output: String, level: Int): String = {
    val space = (0 until level * 2).map(_ => ' ').mkString
    (space + output.replace("\n", s"\n$space")).replaceAll("""\n[ ]+\n""", "\n\n")
  }

  private def isSafeString(str: String): Boolean =
    !(str.indexOf(':') >= 0 || // pretend colon is illegal everywhere for simplicity
      str.indexOf('#') >= 0 || // same for comment
      str.indexOf('!') == 0 ||
      str.indexOf('*') == 0 ||
      str.indexOf('-') == 0 ||
      str.indexOf('?') == 0 ||
      str.indexOf('{') == 0 ||
      str.indexOf('}') == 0 ||
      str.indexOf('[') == 0 ||
      str.indexOf(']') == 0 ||
      str.indexOf(',') == 0 ||
      str.indexOf('|') == 0 ||
      str.indexOf('>') == 0 ||
      str.indexOf('@') == 0 ||
      str.indexOf('`') == 0 ||
      str.indexOf('"') == 0 ||
      str.indexOf('\'') == 0 ||
      str.indexOf('&') == 0)

  private def wrap(str: String): String =
    if (str.indexOf('\n') >= 0)
      "|\n" + indent(str, 1)
    else if (isSafeString(str))
      str
    else
      s"'${str.replace("'", "''")}'"

  def compileList(items: List[String], level: Int): String = {
    val rendered = items.map(wrap)
    if (rendered.map(_.length).sum < 40) // just arbitrarily...
      rendered.mkString(" [", ", ", "]")
    else
      "\n" + indent(rendered.map("- " + _).mkString("\n"), level)
  }

  def compileListOfSimpleDicts(items: List[Map[String, String]]): String =
    items map { dict =>
      val rendered = dict map { case (key, value) => s"$key: $value" } mkString "\n"

      "-" + indent(rendered, 1).substring(1)
    } mkString "\n"

  def compilePREventType(tpe: PREventType): String = {
    import PREventType._

    tpe match {
      case Assigned => "assigned"
      case Unassigned => "unassigned"
      case Labeled => "labeled"
      case Unlabeled => "unlabeled"
      case Opened => "opened"
      case Edited => "edited"
      case Closed => "closed"
      case Reopened => "reopened"
      case Synchronize => "synchronize"
      case ReadyForReview => "ready_for_review"
      case Locked => "locked"
      case Unlocked => "unlocked"
      case ReviewRequested => "review_requested"
      case ReviewRequestRemoved => "review_request_removed"
    }
  }

  def compileRef(ref: Ref): String = ref match {
    case Ref.Branch(name) => s"refs/heads/$name"
    case Ref.Tag(name) => s"refs/tags/$name"
  }

  def compileBranchPredicate(target: String, pred: RefPredicate): String = pred match {
    case RefPredicate.Equals(ref) =>
      s"$target == '${compileRef(ref)}'"

    case RefPredicate.Contains(Ref.Tag(name)) =>
      s"(startsWith($target, 'refs/tags/') && contains($target, '$name'))"

    case RefPredicate.Contains(Ref.Branch(name)) =>
      s"(startsWith($target, 'refs/heads/') && contains($target, '$name'))"

    case RefPredicate.StartsWith(ref) =>
      s"startsWith($target, '${compileRef(ref)}')"

    case RefPredicate.EndsWith(Ref.Tag(name)) =>
      s"(startsWith($target, 'refs/tags/') && endsWith($target, '$name'))"

    case RefPredicate.EndsWith(Ref.Branch(name)) =>
      s"(startsWith($target, 'refs/heads/') && endsWith($target, '$name'))"
  }

  def compileConcurrency(concurrency: Concurrency): String =
    concurrency.cancelInProgress match {
      case Some(value) =>
        val fields = s"""group: ${wrap(concurrency.group)}
                        |cancel-in-progress: ${wrap(value.toString)}""".stripMargin
        s"""concurrency:
           |${indent(fields, 1)}""".stripMargin

      case None =>
        s"concurrency: ${wrap(concurrency.group)}"
    }

  def compileEnvironment(environment: JobEnvironment): String =
    environment.url match {
      case Some(url) =>
        val fields = s"""name: ${wrap(environment.name)}
                        |url: ${wrap(url.toString)}""".stripMargin
        s"""environment:
           |${indent(fields, 1)}""".stripMargin
      case None =>
        s"environment: ${wrap(environment.name)}"
    }

  def compileEnv(env: Map[String, String], prefix: String = "env"): String =
    if (env.isEmpty) {
      ""
    } else {
      val rendered = env map {
        case (key, value) =>
          if (!isSafeString(key) || key.indexOf(' ') >= 0)
            sys.error(s"'$key' is not a valid environment variable name")

          s"""$key: ${wrap(value)}"""
      }
      s"""$prefix:
${indent(rendered.mkString("\n"), 1)}"""
    }

  def compilePermissionScope(permissionScope: PermissionScope): String = permissionScope match {
    case PermissionScope.Actions => "actions"
    case PermissionScope.Checks => "checks"
    case PermissionScope.Contents => "contents"
    case PermissionScope.Deployments => "deployments"
    case PermissionScope.IdToken => "id-token"
    case PermissionScope.Issues => "issues"
    case PermissionScope.Discussions => "discussions"
    case PermissionScope.Packages => "packages"
    case PermissionScope.Pages => "pages"
    case PermissionScope.PullRequests => "pull-requests"
    case PermissionScope.RepositoryProjects => "repository-projects"
    case PermissionScope.SecurityEvents => "security-events"
    case PermissionScope.Statuses => "statuses"
  }

  def compilePermissionsValue(permissionValue: PermissionValue): String =
    permissionValue match {
      case PermissionValue.Read => "read"
      case PermissionValue.Write => "write"
      case PermissionValue.None => "none"
    }

  def compilePermissions(permissions: Option[Permissions]): String = {
    permissions match {
      case Some(perms) =>
        val rendered = perms match {
          case Permissions.ReadAll => " read-all"
          case Permissions.WriteAll => " write-all"
          case Permissions.None => " {}"
          case x: Permissions.Specify =>
            val map = x.asMap.map {
              case (key, value) =>
                s"${compilePermissionScope(key)}: ${compilePermissionsValue(value)}"
            }
            "\n" + indent(map.mkString("\n"), 1)
        }
        s"permissions:$rendered"

      case None => ""
    }
  }

  def compileStep(
      step: WorkflowStep,
      sbt: String,
      sbtStepPreamble: List[String],
      declareShell: Boolean = false): String = {
    import WorkflowStep._

    val renderedName = step.name.map(wrap).map("name: " + _ + "\n").getOrElse("")
    val renderedId = step.id.map(wrap).map("id: " + _ + "\n").getOrElse("")
    val renderedCond = step.cond.map(wrap).map("if: " + _ + "\n").getOrElse("")
    val renderedShell = if (declareShell) "shell: bash\n" else ""

    val renderedEnvPre = compileEnv(step.env)
    val renderedEnv =
      if (renderedEnvPre.isEmpty)
        ""
      else
        renderedEnvPre + "\n"

    val renderedTimeoutMinutes =
      step.timeoutMinutes.map("timeout-minutes: " + _ + "\n").getOrElse("")

    val preamblePre =
      renderedName + renderedId + renderedCond + renderedEnv + renderedTimeoutMinutes

    val preamble =
      if (preamblePre.isEmpty)
        ""
      else
        preamblePre

    val body = step match {
      case run: Run =>
        val renderedWorkingDirectory =
          run.workingDirectory.map(wrap).map("working-directory: " + _ + "\n").getOrElse("")
        renderRunBody(run.commands, run.params, renderedShell, renderedWorkingDirectory)

      case sbtStep: Sbt =>
        import sbtStep.commands

        val preamble = if (sbtStep.preamble) sbtStepPreamble else Nil
        val sbtClientMode = sbt.matches("""sbt.* --client($| .*)""")
        val safeCommands =
          if (sbtClientMode)
            s"'${(preamble ::: commands).mkString("; ")}'"
          else
            (preamble ::: commands)
              .map { c =>
                if (c.indexOf(' ') >= 0)
                  s"'$c'"
                else
                  c
              }
              .mkString(" ")

        renderRunBody(
          commands = List(s"$sbt $safeCommands"),
          params = sbtStep.params,
          renderedShell = renderedShell,
          renderedWorkingDirectory = ""
        )

      case use: Use =>
        import use.{ref, params}

        val decl = ref match {
          case UseRef.Public(owner, repo, ref) =>
            s"uses: $owner/$repo@$ref"

          case UseRef.Local(path) =>
            val cleaned =
              if (path.startsWith("./"))
                path
              else
                "./" + path

            s"uses: $cleaned"

          case UseRef.Docker(image, tag, Some(host)) =>
            s"uses: docker://$host/$image:$tag"

          case UseRef.Docker(image, tag, None) =>
            s"uses: docker://$image:$tag"
        }

        decl + renderParams(params)
    }

    indent(preamble + body, 1).updated(0, '-')
  }

  def renderRunBody(
      commands: List[String],
      params: Map[String, String],
      renderedShell: String,
      renderedWorkingDirectory: String) =
    renderedShell + renderedWorkingDirectory + "run: " + wrap(
      commands.mkString("\n")) + renderParams(params)

  def renderParams(params: Map[String, String]): String = {
    val renderedParamsPre = compileEnv(params, prefix = "with")
    val renderedParams =
      if (renderedParamsPre.isEmpty)
        ""
      else
        "\n" + renderedParamsPre

    renderedParams
  }

  def compileJob(job: WorkflowJob, sbt: String): String = {
    val renderedNeeds =
      if (job.needs.isEmpty)
        ""
      else
        s"\nneeds: [${job.needs.mkString(", ")}]"

    val renderedEnvironment =
      job.environment.map(compileEnvironment).map("\n" + _).getOrElse("")

    val renderedCond = job.cond.map(wrap).map("\nif: " + _).getOrElse("")

    val renderedConcurrency =
      job.concurrency.map(compileConcurrency).map("\n" + _).getOrElse("")

    val renderedContainer = job.container match {
      case Some(JobContainer(image, credentials, env, volumes, ports, options)) =>
        if (credentials.isEmpty && env.isEmpty && volumes.isEmpty && ports.isEmpty && options.isEmpty) {
          "\n" + s"container: ${wrap(image)}"
        } else {
          val renderedImage = s"image: ${wrap(image)}"

          val renderedCredentials = credentials match {
            case Some((username, password)) =>
              s"\ncredentials:\n${indent(s"username: ${wrap(username)}\npassword: ${wrap(password)}", 1)}"

            case None =>
              ""
          }

          val renderedEnv =
            if (env.nonEmpty)
              "\n" + compileEnv(env)
            else
              ""

          val renderedVolumes =
            if (volumes.nonEmpty)
              s"\nvolumes:${compileList(volumes.toList map { case (l, r) => s"$l:$r" }, 1)}"
            else
              ""

          val renderedPorts =
            if (ports.nonEmpty)
              s"\nports:${compileList(ports.map(_.toString), 1)}"
            else
              ""

          val renderedOptions =
            if (options.nonEmpty)
              s"\noptions: ${wrap(options.mkString(" "))}"
            else
              ""

          s"\ncontainer:\n${indent(renderedImage + renderedCredentials + renderedEnv + renderedVolumes + renderedPorts + renderedOptions, 1)}"
        }

      case None =>
        ""
    }

    val renderedEnvPre = compileEnv(job.env)
    val renderedEnv =
      if (renderedEnvPre.isEmpty)
        ""
      else
        "\n" + renderedEnvPre

    val renderedPermPre = compilePermissions(job.permissions)
    val renderedPerm =
      if (renderedPermPre.isEmpty)
        ""
      else
        "\n" + renderedPermPre

    val renderedTimeoutMinutes =
      job.timeoutMinutes.map(timeout => s"\ntimeout-minutes: $timeout").getOrElse("")

    List("include", "exclude") foreach { key =>
      if (job.matrixAdds.contains(key)) {
        sys.error(s"key `$key` is reserved and cannot be used in an Actions matrix definition")
      }
    }

    val renderedMatricesPre = job.matrixAdds.toList.sortBy(_._1) map {
      case (key, values) => s"$key: ${values.map(wrap).mkString("[", ", ", "]")}"
    } mkString "\n"

    // TODO refactor all of this stuff to use whitelist instead
    val whitelist = Map(
      MatrixKeys.OS -> job.oses,
      MatrixKeys.Scala -> job.scalas,
      MatrixKeys.Java -> job.javas.map(_.render)) ++ job.matrixAdds

    def checkMatching(matching: Map[String, String]): Unit = {
      matching foreach {
        case (key, value) =>
          if (!whitelist.contains(key)) {
            sys.error(s"inclusion key `$key` was not found in matrix")
          }

          if (!whitelist(key).contains(value)) {
            sys.error(
              s"inclusion key `$key` was present in matrix, but value `$value` was not in ${whitelist(key)}")
          }
      }
    }

    val renderedIncludesPre = if (job.matrixIncs.isEmpty) {
      renderedMatricesPre
    } else {
      job.matrixIncs.foreach(inc => checkMatching(inc.matching))

      val rendered = compileListOfSimpleDicts(
        job.matrixIncs.map(i => i.matching ++ i.additions))

      val renderedMatrices =
        if (renderedMatricesPre.isEmpty)
          ""
        else
          renderedMatricesPre + "\n"

      s"${renderedMatrices}include:\n${indent(rendered, 1)}"
    }

    val renderedExcludesPre = if (job.matrixExcs.isEmpty) {
      renderedIncludesPre
    } else {
      job.matrixExcs.foreach(exc => checkMatching(exc.matching))

      val rendered = compileListOfSimpleDicts(job.matrixExcs.map(_.matching))

      val renderedIncludes =
        if (renderedIncludesPre.isEmpty)
          ""
        else
          renderedIncludesPre + "\n"

      s"${renderedIncludes}exclude:\n${indent(rendered, 1)}"
    }

    val renderedMatrices =
      if (renderedExcludesPre.isEmpty)
        ""
      else
        "\n" + indent(renderedExcludesPre, 2)

    val declareShell = job.oses.exists(_.contains("windows"))

    val runsOn =
      if (job.runsOnExtraLabels.isEmpty)
        s"$${{ matrix.os }}"
      else
        job.runsOnExtraLabels.mkString(s"""[ "$${{ matrix.os }}", """, ", ", " ]")

    val renderedFailFast = job.matrixFailFast.fold("")("\n  fail-fast: " + _)

    // format: off
    val body = s"""name: ${wrap(job.name)}${renderedNeeds}${renderedCond}
strategy:${renderedFailFast}
  matrix:
${buildMatrix(2, "os" -> job.oses, "scala" -> job.scalas, "java" -> job.javas.map(_.render))}${renderedMatrices}
runs-on: ${runsOn}${renderedEnvironment}${renderedContainer}${renderedPerm}${renderedEnv}${renderedConcurrency}${renderedTimeoutMinutes}
steps:
${indent(job.steps.map(compileStep(_, sbt, job.sbtStepPreamble, declareShell = declareShell)).mkString("\n\n"), 1)}"""
    // format: on

    s"${job.id}:\n${indent(body, 1)}"
  }

  private def buildMatrix(level: Int, prefixWithEntries: (String, List[String])*): String =
    prefixWithEntries
      .collect {
        case (prefix, entries) if entries.nonEmpty =>
          s"$prefix:${compileList(entries, 1)}"
      }
      .map(indent(_, level))
      .mkString("\n")

  def compileWorkflow(
      name: String,
      branches: List[String],
      tags: List[String],
      paths: Paths,
      prEventTypes: List[PREventType],
      permissions: Option[Permissions],
      env: Map[String, String],
      concurrency: Option[Concurrency],
      jobs: List[WorkflowJob],
      sbt: String): String = {

    val renderedPermissionsPre = compilePermissions(permissions)
    val renderedEnvPre = compileEnv(env)
    val renderedEnv =
      if (renderedEnvPre.isEmpty)
        ""
      else
        renderedEnvPre + "\n\n"
    val renderedPerm =
      if (renderedPermissionsPre.isEmpty)
        ""
      else
        renderedPermissionsPre + "\n\n"

    val renderedConcurrency =
      concurrency.map(compileConcurrency).map("\n" + _ + "\n\n").getOrElse("")

    val renderedTypesPre = prEventTypes.map(compilePREventType).mkString("[", ", ", "]")
    val renderedTypes =
      if (prEventTypes.sortBy(_.toString) == PREventType.Defaults)
        ""
      else
        "\n" + indent("types: " + renderedTypesPre, 2)

    val renderedTags =
      if (tags.isEmpty)
        ""
      else
        s"""
    tags: [${tags.map(wrap).mkString(", ")}]"""

    val renderedPaths = paths match {
      case Paths.None =>
        ""
      case Paths.Include(paths) =>
        "\n" + indent(s"""paths: [${paths.map(wrap).mkString(", ")}]""", 2)
      case Paths.Ignore(paths) =>
        "\n" + indent(s"""paths-ignore: [${paths.map(wrap).mkString(", ")}]""", 2)
    }

    s"""# This file was automatically generated by sbt-github-actions using the
# githubWorkflowGenerate task. You should add and commit this file to
# your git repository. It goes without saying that you shouldn't edit
# this file by hand! Instead, if you wish to make changes, you should
# change your sbt build configuration to revise the workflow description
# to meet your needs, then regenerate this file.

name: ${wrap(name)}

on:
  pull_request:
    branches: [${branches.map(wrap).mkString(", ")}]$renderedTypes$renderedPaths
  push:
    branches: [${branches.map(wrap).mkString(", ")}]$renderedTags$renderedPaths

${renderedPerm}${renderedEnv}${renderedConcurrency}jobs:
${indent(jobs.map(compileJob(_, sbt)).mkString("\n\n"), 1)}
"""
  }

  val settingDefaults = Seq(
    githubWorkflowSbtCommand := "sbt",
    githubWorkflowIncludeClean := true,
    // This is currently set to false because of https://github.com/sbt/sbt/issues/6468. When a new SBT version is
    // released that fixes this issue then check for that SBT version (or higher) and set to true.
    githubWorkflowUseSbtThinClient := false,
    githubWorkflowConcurrency := Some(
      Concurrency(
        group = s"$${{ github.workflow }} @ $${{ github.ref }}",
        cancelInProgress = Some(true))
    ),
    githubWorkflowBuildMatrixFailFast := None,
    githubWorkflowBuildMatrixAdditions := Map(),
    githubWorkflowBuildMatrixInclusions := Seq(),
    githubWorkflowBuildMatrixExclusions := Seq(),
    githubWorkflowBuildRunsOnExtraLabels := Seq(),
    githubWorkflowBuildTimeoutMinutes := Some(60),
    githubWorkflowBuildPreamble := Seq(),
    githubWorkflowBuildPostamble := Seq(),
    githubWorkflowBuildSbtStepPreamble := Seq(s"++ $${{ matrix.scala }}"),
    githubWorkflowBuild := Seq(WorkflowStep.Sbt(List("test"), name = Some("Build project"))),
    githubWorkflowPublishPreamble := Seq(),
    githubWorkflowPublishPostamble := Seq(),
    githubWorkflowPublish := Seq(
      WorkflowStep.Sbt(List("+publish"), name = Some("Publish project"))),
    githubWorkflowPublishTargetBranches := Seq(RefPredicate.Equals(Ref.Branch("main"))),
    githubWorkflowPublishCond := None,
    githubWorkflowPublishTimeoutMinutes := None,
    githubWorkflowPublishNeeds := Seq("build"),
    githubWorkflowJavaVersions := Seq(JavaSpec.temurin("11")),
    githubWorkflowScalaVersions := {
      val scalas = crossScalaVersions.value
      val binaryScalas = scalas.map(CrossVersion.binaryScalaVersion(_))
      if (binaryScalas.toSet.size == scalas.size)
        binaryScalas
      else
        scalas
    },
    githubWorkflowOSes := Seq("ubuntu-22.04"),
    githubWorkflowDependencyPatterns := Seq("**/*.sbt", "project/build.properties"),
    githubWorkflowTargetBranches := Seq("**"),
    githubWorkflowTargetTags := Seq(),
    githubWorkflowTargetPaths := Paths.None,
    githubWorkflowEnv := Map("GITHUB_TOKEN" -> s"$${{ secrets.GITHUB_TOKEN }}"),
    githubWorkflowPermissions := None,
    githubWorkflowAddedJobs := Seq()
  )

  private lazy val internalTargetAggregation =
    settingKey[Seq[File]]("Aggregates target directories from all subprojects")

  private val macosGuard = Some("contains(runner.os, 'macos')")
  private val windowsGuard = Some("contains(runner.os, 'windows')")

  private val PlatformSep = FileSystems.getDefault.getSeparator
  private def normalizeSeparators(pathStr: String): String = {
    pathStr.replace(PlatformSep, "/") // *force* unix separators
  }

  private val pathStrs = Def setting {
    val base = (ThisBuild / baseDirectory).value.toPath

    internalTargetAggregation.value map { file =>
      val path = file.toPath

      if (path.isAbsolute)
        normalizeSeparators(base.relativize(path).toString)
      else
        normalizeSeparators(path.toString)
    }
  }

  override def globalSettings =
    Seq(internalTargetAggregation := Seq(), githubWorkflowArtifactUpload := true)

  override def buildSettings = settingDefaults ++ Seq(
    githubWorkflowPREventTypes := PREventType.Defaults,
    githubWorkflowArtifactDownloadExtraKeys := Set.empty,
    githubWorkflowGeneratedUploadSteps := {
      val generate =
        githubWorkflowArtifactUpload.value &&
          githubWorkflowPublishTargetBranches.value.nonEmpty
      if (generate) {
        val sanitized = pathStrs.value map { str =>
          if (str.indexOf(' ') >= 0) // TODO be less naive
            s"'$str'"
          else
            str
        }

        val mkdir = WorkflowStep.Run(
          List(s"mkdir -p ${sanitized.mkString(" ")} project/target"),
          name = Some("Make target directories"),
          cond = Some(publicationCond.value))

        val tar = WorkflowStep.Run(
          List(s"tar cf targets.tar ${sanitized.mkString(" ")} project/target"),
          name = Some("Compress target directories"),
          cond = Some(publicationCond.value))

        val keys = githubWorkflowBuildMatrixAdditions.value.keys.toList.sorted
        val artifactId = MatrixKeys.groupId(keys)

        val upload = WorkflowStep.Use(
          UseRef.Public("actions", "upload-artifact", "v4"),
          name = Some(s"Upload target directories"),
          params = Map("name" -> s"target-$artifactId", "path" -> "targets.tar"),
          cond = Some(publicationCond.value)
        )

        Seq(mkdir, tar, upload)
      } else {
        Seq()
      }
    },
    githubWorkflowGeneratedDownloadSteps := {
      val extraKeys = githubWorkflowArtifactDownloadExtraKeys.value
      val additions = githubWorkflowBuildMatrixAdditions.value
      val matrixAdds = additions.map {
        case (key, values) =>
          if (extraKeys(key))
            key -> values // we want to iterate over all values
          else
            key -> values.take(1) // we only want the primary value
      }

      val oses = githubWorkflowOSes.value.toList.take(1)
      val scalas = githubWorkflowScalaVersions.value.toList
      val javas = githubWorkflowJavaVersions.value.toList.take(1)
      val exclusions = githubWorkflowBuildMatrixExclusions.value.toList

      // we build the list of artifacts, by iterating over all combinations of keys
      val artifacts =
        expandMatrix(
          oses,
          scalas,
          javas,
          matrixAdds,
          Nil,
          exclusions
        ).map {
          case _ :: scala :: _ :: tail => scala :: tail
          case _ => sys.error("Bug generating artifact download steps") // shouldn't happen
        }

      if (githubWorkflowArtifactUpload.value) {
        artifacts flatMap { v =>
          val pretty = v.mkString(", ")

          val download = WorkflowStep.Use(
            UseRef.Public("actions", "download-artifact", "v4"),
            name = Some(s"Download target directories ($pretty)"),
            params =
              Map("name" -> s"target-$${{ matrix.os }}-$${{ matrix.java }}-${v.mkString("-")}")
          )

          val untar = WorkflowStep.Run(
            List("tar xf targets.tar", "rm targets.tar"),
            name = Some(s"Inflate target directories ($pretty)"))

          Seq(download, untar)
        }
      } else {
        Seq()
      }
    },
    githubWorkflowGeneratedCacheSteps := Seq(),
    githubWorkflowJobSetup := {

      val autoCrlfOpt = if (githubWorkflowOSes.value.exists(_.contains("windows"))) {
        List(
          WorkflowStep.Run(
            List("git config --global core.autocrlf false"),
            name = Some("Ignore line ending differences in git"),
            cond = windowsGuard))
      } else {
        Nil
      }

      autoCrlfOpt :::
        List(WorkflowStep.CheckoutFull) :::
        WorkflowStep.SetupSbt ::
        WorkflowStep.SetupJava(githubWorkflowJavaVersions.value.toList) :::
        githubWorkflowGeneratedCacheSteps.value.toList
    },
    githubWorkflowGeneratedCI := {
      val uploadStepsOpt =
        if (githubWorkflowPublishTargetBranches
            .value
            .isEmpty && githubWorkflowAddedJobs.value.isEmpty)
          Nil
        else
          githubWorkflowGeneratedUploadSteps.value.toList

      val publishJobOpt = Seq(
        WorkflowJob(
          "publish",
          "Publish Artifacts",
          githubWorkflowJobSetup.value.toList :::
            githubWorkflowGeneratedDownloadSteps.value.toList :::
            githubWorkflowPublishPreamble.value.toList :::
            githubWorkflowPublish.value.toList :::
            githubWorkflowPublishPostamble.value.toList,
          cond = Some(publicationCond.value),
          oses = githubWorkflowOSes.value.toList.take(1),
          scalas = List.empty,
          sbtStepPreamble = List.empty,
          javas = List(githubWorkflowJavaVersions.value.head),
          needs = githubWorkflowPublishNeeds.value.toList,
          timeoutMinutes = githubWorkflowPublishTimeoutMinutes.value
        )).filter(_ => githubWorkflowPublishTargetBranches.value.nonEmpty)

      Seq(
        WorkflowJob(
          "build",
          "Test",
          githubWorkflowJobSetup.value.toList :::
            githubWorkflowBuildPreamble.value.toList :::
            WorkflowStep.Run(
              List(s"${sbt.value} githubWorkflowCheck"),
              name = Some("Check that workflows are up to date")) ::
            githubWorkflowBuild.value.toList :::
            githubWorkflowBuildPostamble.value.toList :::
            uploadStepsOpt,
          sbtStepPreamble = githubWorkflowBuildSbtStepPreamble.value.toList,
          oses = githubWorkflowOSes.value.toList,
          scalas = githubWorkflowScalaVersions.value.toList,
          javas = githubWorkflowJavaVersions.value.toList,
          matrixFailFast = githubWorkflowBuildMatrixFailFast.value,
          matrixAdds = githubWorkflowBuildMatrixAdditions.value,
          matrixIncs = githubWorkflowBuildMatrixInclusions.value.toList,
          matrixExcs = githubWorkflowBuildMatrixExclusions.value.toList,
          runsOnExtraLabels = githubWorkflowBuildRunsOnExtraLabels.value.toList,
          timeoutMinutes = githubWorkflowBuildTimeoutMinutes.value
        )) ++ publishJobOpt ++ githubWorkflowAddedJobs.value
    }
  )

  private val publicationCond = Def setting {
    val publicationCondPre =
      githubWorkflowPublishTargetBranches
        .value
        .map(compileBranchPredicate("github.ref", _))
        .mkString("(", " || ", ")")

    val publicationCond = githubWorkflowPublishCond.value match {
      case Some(cond) => publicationCondPre + " && (" + cond + ")"
      case None => publicationCondPre
    }
    s"github.event_name != 'pull_request' && $publicationCond"
  }

  private val sbt = Def.setting {
    if (githubWorkflowUseSbtThinClient.value) {
      githubWorkflowSbtCommand.value + " --client"
    } else {
      githubWorkflowSbtCommand.value
    }
  }

  private val generateCiContents = Def task {
    compileWorkflow(
      "Continuous Integration",
      githubWorkflowTargetBranches.value.toList,
      githubWorkflowTargetTags.value.toList,
      githubWorkflowTargetPaths.value,
      githubWorkflowPREventTypes.value.toList,
      githubWorkflowPermissions.value,
      githubWorkflowEnv.value,
      githubWorkflowConcurrency.value,
      githubWorkflowGeneratedCI.value.toList,
      sbt.value
    )
  }

  private val readCleanContents = Def task {
    val src = Source.fromURL(getClass.getResource("/clean.yml"))
    try {
      src.mkString
    } finally {
      src.close()
    }
  }

  private val workflowsDirTask = Def task {
    val githubDir = baseDirectory.value / ".github"
    val workflowsDir = githubDir / "workflows"

    if (!githubDir.exists()) {
      githubDir.mkdir()
    }

    if (!workflowsDir.exists()) {
      workflowsDir.mkdir()
    }

    workflowsDir
  }

  private val ciYmlFile = Def task {
    workflowsDirTask.value / "ci.yml"
  }

  private val cleanYmlFile = Def task {
    workflowsDirTask.value / "clean.yml"
  }

  override def projectSettings = Seq(
    githubWorkflowArtifactUpload := publishArtifact.value,
    Global / internalTargetAggregation ++= {
      if (githubWorkflowArtifactUpload.value)
        Seq(target.value)
      else
        Seq()
    },
    githubWorkflowGenerate / aggregate := false,
    githubWorkflowCheck / aggregate := false,
    githubWorkflowGenerate := {
      val ciContents = generateCiContents.value
      val includeClean = githubWorkflowIncludeClean.value
      val cleanContents = readCleanContents.value

      val ciYml = ciYmlFile.value
      val cleanYml = cleanYmlFile.value

      IO.write(ciYml, ciContents)

      if (includeClean)
        IO.write(cleanYml, cleanContents)
    },
    githubWorkflowCheck := {
      val expectedCiContents = generateCiContents.value
      val includeClean = githubWorkflowIncludeClean.value
      val expectedCleanContents = readCleanContents.value

      val ciYml = ciYmlFile.value
      val cleanYml = cleanYmlFile.value

      val log = state.value.log

      def reportMismatch(file: File, expected: String, actual: String): Unit = {
        log.error(s"Expected:\n$expected")
        log.error(s"Actual:\n${diff(expected, actual)}")
        sys.error(
          s"${file.getName} does not contain contents that would have been generated by sbt-github-actions; try running githubWorkflowGenerate")
      }

      def compare(file: File, expected: String): Unit = {
        val actual = IO.read(file)
        if (expected != actual) {
          reportMismatch(file, expected, actual)
        }
      }

      compare(ciYml, expectedCiContents)

      if (includeClean)
        compare(cleanYml, expectedCleanContents)
    }
  )

  private[sbt] def expandMatrix(
      oses: List[String],
      scalas: List[String],
      javas: List[JavaSpec],
      matrixAdds: Map[String, List[String]],
      includes: List[MatrixInclude],
      excludes: List[MatrixExclude]
  ): List[List[String]] = {
    val keys =
      MatrixKeys.OS :: MatrixKeys.Scala :: MatrixKeys.Java :: matrixAdds.keys.toList.sorted

    val matrix =
      matrixAdds +
        (MatrixKeys.OS -> oses) +
        (MatrixKeys.Scala -> scalas) +
        (MatrixKeys.Java -> javas.map(_.render))

    // expand the matrix
    keys
      .filterNot(matrix.getOrElse(_, Nil).isEmpty)
      .foldLeft(List(List.empty[String])) { (cells, key) =>
        val values = matrix.getOrElse(key, Nil)
        cells.flatMap { cell => values.map(v => cell ::: v :: Nil) }
      }
      .filterNot { cell => // remove the excludes
        val job = keys.zip(cell).toMap
        excludes.exists { // there is an exclude that matches the current job
          case MatrixExclude(matching) => matching.toSet.subsetOf(job.toSet)
        }
      } ::: includes.map { // add the includes
      case MatrixInclude(matching, additions) =>
        // yoloing here, but let's wait for the bug report
        keys.map(matching) ::: additions.values.toList
    }
  }

  private[sbt] def diff(expected: String, actual: String): String = {
    val expectedLines = expected.split("\n", -1)
    val actualLines = actual.split("\n", -1)
    val (lines, _) =
      expectedLines.zipAll(actualLines, "", "").foldLeft((Vector.empty[String], false)) {
        case ((acc, foundDifference), (expectedLine, actualLine))
            if expectedLine == actualLine =>
          (acc :+ actualLine, foundDifference)
        case ((acc, false), ("", actualLine)) =>
          val previousLineLength = acc.lastOption.map(_.length).getOrElse(0)
          val padding = " " * previousLineLength
          val highlight = s"$padding^ (additional lines)"
          (acc :+ highlight :+ actualLine, true)
        case ((acc, false), (_, "")) =>
          val previousLineLength = acc.lastOption.map(_.length).getOrElse(0)
          val padding = " " * previousLineLength
          val highlight = s"$padding^ (missing lines)"
          (acc :+ highlight, true)
        case ((acc, false), (expectedLine, actualLine)) =>
          val sameCount =
            expectedLine.zip(actualLine).takeWhile { case (a, b) => a == b }.length
          val padding = " " * sameCount
          val highlight = s"$padding^ (different character)"
          (acc :+ actualLine :+ highlight, true)
        case ((acc, true), (_, "")) =>
          (acc, true)
        case ((acc, true), (_, actualLine)) =>
          (acc :+ actualLine, true)
      }
    lines.mkString("\n")
  }
}
