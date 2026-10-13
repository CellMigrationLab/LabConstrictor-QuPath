// ================================================================== GUI test body: run identity (SPEC_WRONG_SCIENCE section 4; RUN-01..07, QuPath #16)
// (appended to the tool script by tests/run_gui_test.sh). Needs the `runs` and `frame` test apps (registered by tests/ci_setup_apps.sh in LC_SHARED_HOME; copied into the scratch LC_HOME,
// a folder named lhome_identity). A run is placed on the image that was exported, not on whatever is open when it ends: the DELAYED tool of `runs` blocks on a gate file the test creates, so the
// test changes the open image, the selection, the tool or the app while the run goes, and releases it. The sentences and the code are read from conformance.json (messages.target_unavailable,
// messages.target_changed_size, host_internal_codes, run_identity). RUN-05 (a Napari layer is moved) does not apply to QuPath: an ImageData has no transform.
import javafx.embed.swing.SwingFXUtils
import javax.imageio.ImageIO
import java.util.concurrent.CountDownLatch
import java.awt.image.BufferedImage
import javafx.stage.Window

def FIXTURES = System.getProperty("lc.test.fixtures", new File("tests/fixtures").absolutePath)
def BLOBS = FIXTURES + "/blobs.tif", ASYM = FIXTURES + "/asym.tif", ASYM_ZT = FIXTURES + "/asym_zt.tif"
def SHARED_HOME = new File(System.getenv("LC_SHARED_HOME") ?: System.getenv("LC_HOME"))
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
def waitIdle = { dlg, int seconds = 300 ->
    def end = System.currentTimeMillis() + seconds * 1000
    while (System.currentTimeMillis() < end) { Thread.sleep(200); if (!dlg.running && fx { !dlg.runButton.disable }) return true }
    return false
}
def waitFor = { Closure cond, int seconds = 60 -> def end = System.currentTimeMillis() + seconds * 1000; while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(100) }; return false }
def status = { dlg -> fx { dlg.status.text } }
def pick = { dlg, app, tool -> fx { dlg.appBox.value = app }; fx { dlg.toolBox.value = tool }; Thread.sleep(500) }
def settle = { LcRetention.pruneThreads*.join(20000); LcRetention.pruneThreads.clear() }
def gateOf = { String name -> new File(System.getProperty("java.io.tmpdir"), "lc_gate_" + name + "_" + System.nanoTime()) }
def busy = { dlg -> waitFor({ fx { dlg.worker != null && dlg.worker.currentTask != null } }, 30) }
def release = { File gate -> gate.text = "go" }

Thread.start("lc-gui-test") {
    try {
        def home = new File(System.getenv("LC_HOME")); assert home.path.contains("lhome_identity")
        home.deleteDir(); def apps = new File(home, "apps"); apps.mkdirs()
        def srcApps = new File(SHARED_HOME, "apps")
        ["runs", "frame"].each { name ->
            new File(apps, name + ".schema.json").text = new File(srcApps, name + ".schema.json").text
            new File(apps, name + ".json").text = LcJson.toJson(LcJson.parse(new File(srcApps, name + ".json")) + [schema_path: new File(apps, name + ".schema.json").path])
            ["chmod", "644", new File(apps, name + ".json").path, new File(apps, name + ".schema.json").path].execute().waitFor()
        }
        def PY = (LcJson.parse(new File(apps, "runs.json")) as Map).python as String
        def confPath = System.getenv("LC_CONFORMANCE") ?: [PY, "-m", "labconstrictor_tools.conformance", "--path"].execute().text.trim()
        def CONF = LcJson.parse(new File(confPath))
        def runCase = { String id -> def c = CONF.wrong_science.run_identity.fixtures.find { it.id == id }; assert c != null : "conformance.json has no case " + id; c }
        expect("conformance_has_RUN_01_to_07", (1..7).every { runCase(String.format("RUN-%02d", it)) != null }, CONF.wrong_science.run_identity.keySet())
        expect("the_sentences_are_the_conformance_sentences", LcRun.TARGET_UNAVAILABLE == CONF.messages.target_unavailable.template && LcRun.TARGET_CHANGED_SIZE == CONF.messages.target_changed_size.template, [CONF.messages.target_unavailable, CONF.messages.target_changed_size])
        expect("the_code_is_the_conformance_code_and_RUN_07_names_it", LcRun.CODE_TARGET_UNAVAILABLE in CONF.host_internal_codes && runCase("RUN-07").code == LcRun.CODE_TARGET_UNAVAILABLE && runCase("RUN-07").message == "target_changed_size" && runCase("RUN-04").message == "target_unavailable", CONF.host_internal_codes)
        def sentence = { String key, String runId, String source, String path -> CONF.messages[key].template.replace("{run_id}", runId).replace("{source}", source).replace("{path}", path) }

        // ---- a project with the two images A (blobs.tif, 80 x 60) and B (asym.tif, 12 x 8)
        def entries = [:]
        fx {
            def dir = new File(System.getProperty("java.io.tmpdir"), "qp_project_identity"); dir.deleteDir()
            qupath.lib.gui.prefs.PathPrefs.imageTypeSettingProperty().set(qupath.lib.gui.prefs.PathPrefs.ImageTypeSetting.AUTO_ESTIMATE)
            def project = qupath.lib.projects.Projects.createProject(dir, BufferedImage)
            [["blobs.tif", BLOBS], ["asym.tif", ASYM], ["asym_zt.tif", ASYM_ZT]].each { n, f ->
                def s = qupath.lib.images.servers.ImageServers.buildServer(f); def e = project.addImage(s.getBuilder()); e.setImageName(n); entries[n] = e
            }
            project.syncChanges(); qupath.setProject(project)
        }
        def openImage = { String name ->
            fx { qupath.imageData?.setChanged(false); qupath.openImageEntry(qupath.project.imageList.find { it.imageName == name }); null }       // unsaved annotations would ask "save changes?" and wait for a click
            Thread.sleep(2500)
            fx { qupath.imageData.hierarchy.clearAll(); null }
        }
        def hierarchy = { fx { qupath.imageData.hierarchy } }
        def marksOn = { def data -> fx { data.hierarchy.annotationObjects.findAll { it.name?.startsWith("runs:marks") } } }
        def resultsRoot = new File(home, "results"), runsRoot = new File(home, "runs")
        def newest = { File root -> (root.listFiles() ?: []).findAll { it.isDirectory() }.max { it.name } }
        LcRetention.pruneCommandFor = { Map entry -> [PY, "-c", "pass"] as List<String> }      // no real prune here (the retention body checks it)
        openImage("blobs.tif")
        def dataA = fx { qupath.imageData }
        def dlg = fx { lcShow() }
        Thread.sleep(800)
        pick(dlg, "runs", "Delayed points")
        def chooserA = fx { dlg.imageBoxes["image"].value as String }
        expect("the_chooser_names_the_open_image", chooserA == "Current image: blobs.tif", chooserA)

        // ---- RUN-06 (first half) and the plain run: the points land on A, carry the run id, the app and the tool of this run
        def gate0 = gateOf("plain"); release(gate0)
        fx { dlg.setters["gate"](gate0.path); dlg.setters["y"](10d); dlg.setters["x"](20d) }
        fx { dlg.run() }; Thread.sleep(500); waitIdle(dlg, 120); settle()
        def idA = fx { dlg.runSnapshot.runId }
        def a1 = marksOn(dataA)
        expect("RUN-06_run_1_draws_its_point_on_A", status(dlg).startsWith("done") && a1.size() == 1 && Math.abs(a1[0].ROI.centroidX - 20.5d) < 1e-9 && Math.abs(a1[0].ROI.centroidY - 10.5d) < 1e-9, [status(dlg), a1.size()])
        expect("RUN-06_the_point_carries_the_run_id_the_app_and_the_tool", a1[0].metadata["labconstrictor.run_id"] == idA && a1[0].metadata["labconstrictor.app"] == "runs" && a1[0].metadata["labconstrictor.tool"] == "delayed_points" && idA ==~ /^[0-9a-f-]{36}$/, a1[0].metadata)
        expect("the_run_record_has_the_same_run_id", (LcJson.parse(new File(newest(runsRoot), "run.json")) as Map).run_id == idA, "")

        // ---- RUN-01 / RUN-04: start on A, open B while the tool runs: nothing is drawn on B, nothing on A (A is not open any more), the files are kept, the status is the conformance sentence
        fx { hierarchy().clearAll() }
        def gate1 = gateOf("r1")
        fx { dlg.setters["gate"](gate1.path) }
        def before1 = [resultsRoot.list().size(), runsRoot.list().size()]
        fx { dlg.run() }
        busy(dlg)
        def run1 = fx { dlg.runSnapshot }
        openImage("asym.tif")
        def dataB = fx { qupath.imageData }
        release(gate1); waitIdle(dlg, 120); settle()
        def sentence1 = sentence("target_unavailable", run1.runId, "blobs.tif", run1.resultsPath)
        expect("RUN-01_the_status_is_the_target_unavailable_sentence", status(dlg) == sentence1, [status(dlg), sentence1])
        expect("RUN-01_nothing_is_drawn_on_B", marksOn(dataB).isEmpty() && fx { dataB.hierarchy.annotationObjects.isEmpty() }, marksOn(dataB)*.name)
        expect("RUN-01_nothing_is_drawn_on_A_either_it_is_not_open", marksOn(dataA).isEmpty(), marksOn(dataA)*.name)
        expect("RUN-04_the_outputs_are_kept_in_the_owned_results_folder", new File(run1.resultsPath, "marks.csv").isFile() && new File(run1.resultsPath, ".lc-done.json").isFile() && run1.resultsPath.startsWith(resultsRoot.path), run1.resultsPath)
        expect("RUN-04_the_run_record_is_written_with_the_run_id", resultsRoot.list().size() == before1[0] + 1 && runsRoot.list().size() == before1[1] + 1 && (LcJson.parse(new File(newest(runsRoot), "run.json")) as Map).run_id == run1.runId, "")
        expect("RUN-04_the_code_is_logged_and_no_exception_was_raised", LcLog.recent.any { it.contains("[target_unavailable]") && it.contains(run1.runId) } && !(fx { dlg.lastReport } as String).contains("host_error"), LcLog.recent.takeRight(3))
        expect("RUN-04_the_results_window_still_lists_the_points_table", fx { dlg.lastResultStage?.scene?.root != null } && fx { dlg.lastResultStage.userData } == run1.runId, "")
        expect("RUN-01_the_chooser_follows_the_open_image", fx { dlg.imageBoxes["image"].value as String } == "Current image: asym.tif" && fx { dlg.formSources.keySet().findAll { it.startsWith("Current image") }.toList() } == ["Current image: asym.tif"], fx { dlg.imageBoxes["image"].value })

        // ---- RUN-06 (second half): the next run is on B and lands on B with its own run id; A's earlier point is not touched
        def gate2 = gateOf("r6b"); release(gate2)
        fx { dlg.setters["gate"](gate2.path); dlg.setters["y"](2d); dlg.setters["x"](3d) }
        fx { dlg.run() }; Thread.sleep(500); waitIdle(dlg, 120); settle()
        def idB = fx { dlg.runSnapshot.runId }
        def bMarks = marksOn(dataB)
        expect("RUN-06_run_2_lands_on_B_with_its_own_run_id", status(dlg).startsWith("done") && bMarks.size() == 1 && idB != idA && idB != run1.runId && bMarks[0].metadata["labconstrictor.run_id"] == idB && Math.abs(bMarks[0].ROI.centroidX - 3.5d) < 1e-9, [status(dlg), bMarks*.metadata])
        expect("RUN-06_A_got_nothing_from_run_2", marksOn(dataA).isEmpty(), marksOn(dataA)*.name)

        // ---- RUN-01 (else-branch): A is still open in a second viewer: the points go to A (the image the run started on), not to the image that is active
        def second = fx { try { qupath.viewerManager.setGridSize(1, 2); qupath.allViewers.size() == 2 } catch (Throwable t) { println "TWO VIEWERS: " + t; false } }
        if (second) {
            fx { qupath.viewerManager.setActiveViewer(qupath.allViewers[0]); null }
            openImage("blobs.tif")
            fx { LcRun.refreshChoosers(dlg); null }
            def a2 = fx { qupath.imageData }
            def gate3 = gateOf("r1b")
            fx { dlg.setters["gate"](gate3.path); dlg.setters["y"](5d); dlg.setters["x"](6d) }
            fx { dlg.run() }; busy(dlg)
            fx { qupath.viewerManager.setActiveViewer(qupath.allViewers[1]); null }
            fx { qupath.openImageEntry(qupath.project.imageList.find { it.imageName == "asym.tif" }); null }; Thread.sleep(2500)
            release(gate3); waitIdle(dlg, 120); settle()
            def onA = marksOn(a2)
            expect("RUN-01_A_still_open_in_another_viewer_gets_the_points_and_the_active_B_gets_nothing", status(dlg).startsWith("done") && onA.size() == 1 && marksOn(fx { qupath.imageData }).isEmpty() && onA[0].metadata["labconstrictor.run_id"] == fx { dlg.runSnapshot.runId }, [status(dlg), onA.size()])
            fx { qupath.viewerManager.resetGridSize(); qupath.viewerManager.setActiveViewer(qupath.allViewers[0]); qupath.allViewers.drop(1).each { it.imageData?.setChanged(false); it.setImageData(null) }; null }      // one viewer again: the hidden ones hold no image
        } else println "UNSUPPORTED RUN-01_second_viewer: this harness cannot show two viewers"
        openImage("blobs.tif")
        fx { LcRun.refreshChoosers(dlg); null }

        // ---- RUN-04 (closed, not replaced): A is closed with no other image
        def gate4 = gateOf("r4"); fx { dlg.setters["gate"](gate4.path); dlg.setters["y"](1d); dlg.setters["x"](1d) }
        fx { dlg.run() }; busy(dlg)
        def run4 = fx { dlg.runSnapshot }
        fx { qupath.allViewers.each { it.imageData?.setChanged(false); it.setImageData(null) }; null }; Thread.sleep(500)      // closed in every viewer
        release(gate4); waitIdle(dlg, 120); settle()
        expect("RUN-04_closing_A_gives_the_target_unavailable_sentence_and_keeps_the_files", status(dlg) == sentence("target_unavailable", run4.runId, "blobs.tif", run4.resultsPath) && new File(run4.resultsPath, "marks.csv").isFile(), status(dlg))
        fx { dlg.run() }; Thread.sleep(500)
        expect("a_run_with_no_image_open_is_refused_not_started_on_another_entry", status(dlg).contains("'blobs.tif' is no longer the image open in QuPath (no image is open): choose the image again") && !fx { dlg.running } && fx { dlg.imageBoxes["image"].value as String } == "Current image: blobs.tif", status(dlg))
        openImage("blobs.tif")
        fx { LcRun.refreshChoosers(dlg); null }
        expect("the_chooser_names_the_image_again_when_it_is_reopened", fx { dlg.imageBoxes["image"].value as String } == "Current image: blobs.tif", fx { dlg.imageBoxes["image"].value })

        // ---- RUN-03: switch the tool while the run goes: the result carries the tool and app of the run, and the end of the run does not clear the fields of the tool that is shown now
        def gate3 = gateOf("r3"); fx { dlg.setters["gate"](gate3.path); dlg.setters["y"](7d) ; dlg.setters["x"](8d) }
        fx { dlg.run() }; busy(dlg)
        def run3 = fx { dlg.runSnapshot }
        pick(dlg, "runs", "Quick values")
        fx { dlg.setters["n"](5) }
        release(gate3); waitIdle(dlg, 120); settle()
        def drawn3 = marksOn(fx { qupath.imageData })
        expect("RUN-03_the_result_is_placed_and_carries_the_tool_and_app_of_the_run", drawn3.size() == 1 && drawn3[0].name == "runs:marks" && drawn3[0].metadata["labconstrictor.tool"] == "delayed_points" && drawn3[0].metadata["labconstrictor.app"] == "runs" && drawn3[0].metadata["labconstrictor.run_id"] == run3.runId, [drawn3*.metadata, status(dlg)])
        expect("RUN-03_the_form_of_the_other_tool_is_not_cleared_by_the_end_of_the_run", fx { dlg.currentTool.id } == "quick_values" && fx { dlg.getters["n"]() } == 5 && !fx { LcRun.formShowsToolOf(dlg) }, [fx { dlg.currentTool.id }, fx { dlg.getters["n"]() }])
        expect("RUN-03_the_run_record_names_the_tool_of_the_run", (LcJson.parse(new File(newest(runsRoot), "run.json")) as Map).with { app == "runs" && tool == "delayed_points" && run_id == run3.runId }, "")
        // ... and the app: start on `runs`, switch to the `frame` app, release: the result is still the run's
        pick(dlg, "runs", "Delayed points")
        fx { hierarchy().clearAll() }
        def gate3b = gateOf("r3b"); fx { dlg.setters["gate"](gate3b.path); dlg.setters["y"](9d) ; dlg.setters["x"](9d) }
        fx { dlg.run() }; busy(dlg)
        def run3b = fx { dlg.runSnapshot }
        pick(dlg, "frame", "Frame probe")
        release(gate3b); waitIdle(dlg, 120); settle()
        def drawn3b = marksOn(fx { qupath.imageData })
        expect("RUN-03_switching_the_app_keeps_the_app_and_tool_of_the_run", drawn3b.size() == 1 && drawn3b[0].metadata["labconstrictor.app"] == "runs" && drawn3b[0].metadata["labconstrictor.tool"] == "delayed_points" && drawn3b[0].metadata["labconstrictor.run_id"] == run3b.runId && fx { dlg.currentTool.id } == "probe", [drawn3b*.metadata, status(dlg)])

        // ---- RUN-02: the selection changes while the export goes on: the crop and the mask are those of the click
        pick(dlg, "frame", "Frame probe")
        fx { hierarchy().clearAll() }
        def rect = { double x, double y, double w, double h -> qupath.lib.objects.PathObjects.createAnnotationObject(qupath.lib.roi.ROIs.createRectangleROI(x, y, w, h, qupath.lib.regions.ImagePlane.getDefaultPlane())) }
        def r1 = fx { rect(10, 5, 20, 10) }, r2 = fx { rect(40, 30, 7, 9) }
        fx { hierarchy().addObjects([r1, r2]); hierarchy().selectionModel.setSelectedObjects([r1], null); null }
        def changeSelectionAt = { List<String> stages -> LcRun.barrier = { String stage -> if (stage in stages) { Platform.runLater { hierarchy().selectionModel.setSelectedObjects([r2], null) }; Thread.sleep(800) } } }
        // (a) Image area: Selected annotation(s): the crop is r1's box (20 x 10), though r2 (7 x 9) is selected from the moment the run thread starts
        fx { dlg.selectionBoxes["region"].selected = false; dlg.areaBoxes["image"].value = "Selected annotation(s)"; dlg.setters["y"](1d); dlg.setters["x"](1d); null }
        changeSelectionAt(["prepare", "export"])
        fx { dlg.run() }; Thread.sleep(500); waitIdle(dlg, 120); settle()
        LcRun.barrier = null
        def seen = (LcJson.parseText((fx { dlg.lastReport } as String).split("\n\nworker output:")[0]) as Map).outputs?.results?.find { it.type == "values" }?.values
        expect("RUN-02_the_exported_crop_is_the_selection_of_the_click", seen != null && seen.image_shape == [10, 20] && fx { hierarchy().selectionModel.selectedObjects.toList() } == [r2], [seen, status(dlg)])
        expect("RUN-02_the_results_are_placed_with_the_offset_of_the_click", fx { hierarchy().annotationObjects.findAll { it.name?.startsWith("frame:marks") }.collectMany { it.ROI.allPoints } }.with { size() == 1 && Math.abs(get(0).x - 11.5d) < 1e-9 && Math.abs(get(0).y - 6.5d) < 1e-9 }, status(dlg))
        // (b) RegionOf ("use the selection"): the mask is r1 (200 pixels), not r2 (63)
        fx { hierarchy().selectionModel.setSelectedObjects([r1], null); dlg.areaBoxes["image"].value = "Whole image"; dlg.selectionBoxes["region"].selected = true; null }
        changeSelectionAt(["prepare", "export"])
        fx { dlg.run() }; Thread.sleep(500); waitIdle(dlg, 120); settle()
        LcRun.barrier = null
        def seenMask = (LcJson.parseText((fx { dlg.lastReport } as String).split("\n\nworker output:")[0]) as Map).outputs?.results?.find { it.type == "values" }?.values
        expect("RUN-02_the_mask_is_the_selection_of_the_click", seenMask != null && seenMask.mask_pixels.size() == 200 && seenMask.mask_shape == [60, 80], seenMask?.mask_shape)
        fx { dlg.selectionBoxes["region"].selected = false; null }

        // ---- RUN-07: A stays open but its data changes shape while the tool runs (QuPath has no way to do this but a script that replaces the server: the test does it with reflection)
        pick(dlg, "runs", "Delayed points")
        fx { hierarchy().clearAll() }
        def dataCur = fx { qupath.imageData }
        def gate7 = gateOf("r7"); fx { dlg.setters["gate"](gate7.path) }
        fx { dlg.run() }; busy(dlg)
        def run7 = fx { dlg.runSnapshot }
        def realServer = dataCur.server
        def other = qupath.lib.images.servers.ImageServers.buildServer(ASYM)
        def field = qupath.lib.images.ImageData.getDeclaredField("server"); field.setAccessible(true); field.set(dataCur, other)
        release(gate7); waitIdle(dlg, 120); settle()
        field.set(dataCur, realServer)
        expect("RUN-07_the_status_is_the_changed_size_sentence_not_the_no_longer_open_one", status(dlg) == sentence("target_changed_size", run7.runId, "blobs.tif", run7.resultsPath) && !status(dlg).contains("no longer open"), status(dlg))
        expect("RUN-07_nothing_is_drawn_anywhere", marksOn(dataCur).isEmpty() && marksOn(fx { qupath.imageData }).isEmpty(), marksOn(dataCur)*.name)
        expect("RUN-07_files_are_kept_in_the_owned_folder_and_the_record_is_written", new File(run7.resultsPath, "marks.csv").isFile() && new File(run7.resultsPath, ".lc-done.json").isFile() && (LcJson.parse(new File(newest(runsRoot), "run.json")) as Map).run_id == run7.runId, "")
        expect("RUN-07_the_code_is_target_unavailable", LcLog.recent.any { it.contains("[target_unavailable]") && it.contains("changed size") && it.contains(run7.runId) }, LcLog.recent.takeRight(3))
        def gate8 = gateOf("r8"); release(gate8); fx { dlg.setters["gate"](gate8.path); dlg.setters["y"](3d); dlg.setters["x"](3d) }
        fx { dlg.run() }; Thread.sleep(500); waitIdle(dlg, 120); settle()
        expect("RUN-07_the_image_with_its_real_shape_takes_results_again", status(dlg).startsWith("done") && marksOn(dataCur).size() == 1, status(dlg))

        // ---- the chooser text is never identity: a run whose chooser names an image that is not the open one is refused before anything starts
        def count = [resultsRoot.list().size(), runsRoot.list().size()]
        def refusal = null
        try { fx { dlg.imageChoiceAtRun = [image: "Current image: asym_zt.tif"]; LcRun.take(dlg, [image: [source: { null }]]) } } catch (LcRefusal e) { refusal = e.message }
        expect("a_stale_chooser_label_is_refused", refusal != null && refusal.contains("'asym_zt.tif' is no longer the image open in QuPath") && refusal.contains("choose the image again"), refusal)
        expect("a_refused_freeze_made_no_folder", resultsRoot.list().size() == count[0] && runsRoot.list().size() == count[1], count)

    } catch (Throwable t) { t.printStackTrace(); results["EXCEPTION"] = false }
    finally { println "RESULTS " + results; Thread.sleep(500); System.exit(results && results.every { it.value } ? 0 : 1) }
}
