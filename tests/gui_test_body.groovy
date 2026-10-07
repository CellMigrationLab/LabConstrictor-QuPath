
// ================================================================== GUI test body (appended to the tool script by tests/run_gui_test.sh)
import javafx.embed.swing.SwingFXUtils
import javax.imageio.ImageIO
import java.util.concurrent.CountDownLatch
import java.awt.image.BufferedImage
import javafx.stage.Window

def OUT = new File(System.getProperty("lc.test.out", "/tmp/qp_shots")); OUT.mkdirs()
def FX = "/home/tester/q/fx"
def results = [:]
def fx = { Closure c ->
    if (Platform.isFxApplicationThread()) return c()
    def latch = new CountDownLatch(1); def box = [null, null]
    Platform.runLater { try { box[0] = c() } catch (Throwable t) { box[1] = t } finally { latch.countDown() } }
    latch.await()
    if (box[1] != null) throw box[1]
    return box[0]
}
def expect = { String name, boolean ok, Object detail = "" ->
    results[name] = ok
    println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : " -> " + detail))
}
def shot = { String name, Stage st ->
    Thread.sleep(600)
    fx {
        def img = st.scene.snapshot(null)
        ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", new File(OUT, name + ".png"))
    }
    println "SHOT " + name
}
def waitIdle = { dlg, int seconds = 300 ->
    def end = System.currentTimeMillis() + seconds * 1000
    while (System.currentTimeMillis() < end) { Thread.sleep(200); if (!dlg.running && fx { !dlg.runButton.disable }) return true }
    return false
}
def status = { dlg -> fx { dlg.status.text } }
def pick = { dlg, app, tool -> fx { dlg.appBox.value = app }; fx { dlg.toolBox.value = tool } }
def resultStages = { Window.getWindows().findAll { it instanceof Stage && it.showing && it.title?.contains(": ") && it.title != "LabConstrictor: last run" } }

Thread.start("lc-gui-test") {
    try {
        // a project with the two calibrated images; the reference is open in the viewer
        def entries = fx {
            def dir = new File("/tmp/qp_project"); dir.deleteDir()
            qupath.lib.gui.prefs.PathPrefs.imageTypeSettingProperty().set(qupath.lib.gui.prefs.PathPrefs.ImageTypeSetting.AUTO_ESTIMATE)  // no modal 'set image type' prompt
            def project = qupath.lib.projects.Projects.createProject(dir, BufferedImage)
            def es = ["reference", "query"].collect { n ->
                def server = qupath.lib.images.servers.ImageServers.buildServer(FX + "/" + n + ".tif")
                def entry = project.addImage(server.getBuilder())
                entry.setImageName(n + ".tif")   // the QuPath dialogs name project images; addImage alone leaves the name unset
                entry
            }
            project.syncChanges()
            qupath.setProject(project)
            qupath.openImageEntry(es[0])
            es
        }
        Thread.sleep(2500)
        def dlg = fx { lcShow() }
        Thread.sleep(800)
        expect("Q1_apps_listed", fx { dlg.appBox.items as List } == ["CellTracksColab", "NucleiSky"], fx { dlg.appBox.items as List })
        pick(dlg, "NucleiSky", "Relocalize 2D")
        Thread.sleep(500)
        def refChoices = fx { dlg.imageBoxes["reference"].items as List }
        expect("Q2_image_choices", refChoices.any { it.startsWith("Current image: reference.tif") } && refChoices.contains("Project: query.tif") && refChoices.contains("File..."), refChoices)
        expect("Q2_required_image_preselected", fx { dlg.imageBoxes["reference"].value }.startsWith("Current image"), fx { dlg.imageBoxes["reference"].value })
        expect("Q2_optional_labels_start_on_none", fx { dlg.imageBoxes["reference_mask"].value } == "(none)")
        fx { dlg.imageBoxes["query"].value = "Project: query.tif" }
        Thread.sleep(500)
        def refPx = fx { dlg.getters["reference_pixel_size_um"]() }, qPx = fx { dlg.getters["query_pixel_size_um"]() }
        expect("Q3_pixel_size_from_image", Math.abs(refPx - 0.65) < 1e-6 && Math.abs(qPx - 0.325) < 1e-6, [refPx, qPx])
        expect("Q4_threshold_fields_enabled", fx { !dlg.wrappers["blur_sigma"].disable && !dlg.wrappers["segmentation"].disable })
        fx { dlg.controls["segmentation"].value = "instanseg" }
        Thread.sleep(300)
        expect("Q4_threshold_fields_greyed_for_instanseg", fx { dlg.wrappers["blur_sigma"].disable && !dlg.wrappers["instanseg_target"].disable && !dlg.wrappers["matcher"].disable })
        fx { dlg.controls["segmentation"].value = "threshold" }
        Thread.sleep(300)
        shot("1_form_nucleisky", dlg.stage)
        fx { dlg.formBox.children.findAll { it instanceof TitledPane }.each { it.expanded = true } }
        Thread.sleep(500)
        shot("2_form_advanced_open", dlg.stage)
        fx { dlg.formBox.children.findAll { it instanceof TitledPane }.each { it.expanded = false } }

        // run on the open image + a project image
        fx { dlg.run() }
        expect("Q5_run_completes", waitIdle(dlg), status(dlg))
        def text = status(dlg)
        println "STATUS " + text
        expect("Q5_numbers", text.startsWith("done") && text.contains("n_nuclei_reference=429") && text.contains("n_nuclei_query=9") && text.contains("matcher=quad"), text)
        def stages = fx { resultStages() }
        expect("Q5_results_window", stages.size() == 1, stages*.title)
        if (stages) shot("3_results_nucleisky", stages[0])
        fx { stages.each { it.close() } }
        def lastReport = fx { dlg.lastReport }
        def qa = (lastReport =~ /"path": "([^"]*query_aligned\.tif)"/)
        expect("Q5_aligned_image_written", qa.find() && new File(qa.group(1)).isFile(), lastReport.take(300))

        // no match is a message, not an error
        fx { dlg.controls["matcher"].value = "triangles" }
        fx { dlg.run() }
        expect("Q6_no_match_finishes", waitIdle(dlg), status(dlg))
        text = status(dlg)
        expect("Q6_no_match_is_a_message", text.startsWith("no result:") && fx { !dlg.runButton.disable }, text)
        def alerts = fx { Window.getWindows().findAll { it instanceof Stage && it.showing && it.scene?.root instanceof DialogPane } }
        if (alerts) shot("4_no_match_message", alerts[0])
        fx { alerts.each { it.close() } }
        fx { dlg.controls["matcher"].value = "auto" }

        // an input that is missing is explained
        fx { dlg.imageBoxes["query"].value = "File..." }
        fx { dlg.run() }
        Thread.sleep(500)
        expect("Q7_file_required_message", status(dlg).contains("is required"), status(dlg))
        fx { dlg.imageBoxes["query"].value = "Project: query.tif" }

        // cancel while segmenting
        fx { dlg.run() }
        def end = System.currentTimeMillis() + 30000
        while (System.currentTimeMillis() < end && !status(dlg).toLowerCase().contains("segment")) Thread.sleep(50)
        fx { dlg.cancel() }
        expect("Q8_cancel_finishes", waitIdle(dlg, 60), status(dlg))
        expect("Q8_cancelled", status(dlg).startsWith("cancelled"), status(dlg))
        shot("5_cancelled", dlg.stage)

        // second app: a table result
        pick(dlg, "CellTracksColab", "Calculate Track Metrics")
        Thread.sleep(500)
        fx { dlg.setters["tracks"]("/home/tester/humantest/ct/tracks.csv") }
        shot("6_form_celltracks", dlg.stage)
        fx { dlg.run() }
        expect("Q9_celltracks_completes", waitIdle(dlg), status(dlg))
        text = status(dlg)
        expect("Q9_celltracks_done", text.startsWith("done"), text)
        stages = fx { resultStages() }
        expect("Q9_table_window", stages.size() == 1 && fx { stages[0].scene.root.lookupAll(".table-view").size() } == 1, stages*.title)
        if (stages) shot("7_results_celltracks_table", stages[0])
        fx { stages.each { it.close() } }
        fx { dlg.setters["tracks"]("/nonexistent/none.csv") }
        fx { dlg.run() }
        expect("Q10_missing_table_explained", waitIdle(dlg) && status(dlg).startsWith("failed: ") && status(dlg).contains("file not found"), status(dlg))

        // the workers go away with the dialog
        fx { dlg.stage.close() }
        Thread.sleep(3000)
        def left = ["bash", "-c", "pgrep -f '[l]abconstrictor_tools serve' | wc -l"].execute().text.trim()
        expect("Q11_no_worker_left", left == "0", left)
    } catch (Throwable t) {
        t.printStackTrace()
        results["EXCEPTION"] = false
        println "FAIL EXCEPTION -> " + t
    } finally {
        println "SUMMARY " + results.count { it.value } + "/" + results.size() + " passed"
        Thread.sleep(500)
        System.exit(results.every { it.value } ? 0 : 1)
    }
}
