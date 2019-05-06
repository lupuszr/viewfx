package viewfx

import java.io.File
import java.lang

import scalafx.application.JFXApp
import scalafx.application.JFXApp.PrimaryStage
import scalafx.geometry.Insets
import scalafx.scene.Scene
import scalafx.scene.effect.DropShadow
import scalafx.scene.layout.{HBox, Priority, VBox}
import scalafx.scene.paint.Color._
import scalafx.scene.paint._
import scalafx.scene.text.Text
import scalafx.event.ActionEvent
import scalafx.scene.image._
import scalafx.scene.control._
import scalafx.stage.FileChooser
import scalafx.stage.DirectoryChooser
import scalafx.Includes._
import javafx.scene.{image => jfxsi}
import scalafx.beans.binding.Bindings
import cats.effect.IO
import javafx.beans.InvalidationListener
import javafx.beans.value.{ChangeListener, ObservableBooleanValue}
import monocle.macros.Lenses
import scalafx.beans.property.ObjectProperty
import scalafx.beans.property.DoubleProperty
import scalafx.collections.ObservableBuffer
import java.util.UUID
import java.util.UUID.randomUUID

import scalafx.scene.input.ScrollEvent
import monocle.Lens
//import cats.data.State

@Lenses("_") final case class AppState(directoryPath: DirectoryPath, currentFile: CurrentFile, directoryMenu: DirectoryMenu)
@Lenses("_") final case class DirectoryPath(path: Option[String])
@Lenses("_") final case class CurrentFile(file: Option[File])

@Lenses("_") final case class DirectoryMenu(visible: DirectoryMenuVisible, files: FileTree)
@Lenses("_") final case class FileTree(files: Array[File], version: UUID)
@Lenses("_") final case class DirectoryMenuVisible(visible: Boolean)

class IORef {
  var a: AppState = AppState(
    DirectoryPath(None),
    CurrentFile(None),
    DirectoryMenu(
      DirectoryMenuVisible(true),
      FileTree(Array.empty, randomUUID())
    )
  )
  def unsafeSet(s: AppState): Unit = {
    a = s
  }
  def set(s: IO[AppState]): IO[Unit] = {
    for {
      state <- s
      r = unsafeSet(state)
    } yield (r)
  }
  def get: IO[AppState] = IO.pure(a)
}

class IOModel {
  private val DefaultImageURL = "https://amp.businessinsider.com/images/5bbbaa1194750c0f7033b6a8-1920-1440.jpg"
  private val DefaultImageCover = new Image(DefaultImageURL)
  val activeImage: ObjectProperty[jfxsi.Image] = ObjectProperty[jfxsi.Image](this, "image")
  val zoom: DoubleProperty = DoubleProperty(100)
  val fileTree: ObservableBuffer[String] = new ObservableBuffer[String]()
  var fileTreeVersion: UUID = randomUUID();

  resetProperties()

  private def resetProperties(): Unit = {
    activeImage() = DefaultImageCover
    fileTree.clear()
  }
}

object MainApp extends JFXApp {
  stage = new PrimaryStage {

    val model = new IOModel() {}
    //    initStyle(StageStyle.Unified)
    val ref = new IORef()
    title = "ViewFx"
    val fileMenu = new Menu("File")
    val aboutMenu = new Menu("About")
    val menuBar = new MenuBar()

    menuBar.getMenus().addAll(fileMenu, aboutMenu)

    val fileMenuOpen = new MenuItem("Open")
    val fileMenuDirectoryOpen = new MenuItem("Open directory")
    fileMenu.getItems().addAll(fileMenuOpen, fileMenuDirectoryOpen)

    val fileListView = new ListView(model.fileTree)
    fileListView.prefWidth = 150

    val view = new ImageView()
    view.fitWidth <== this.width - 150

    view.preserveRatio = true
    view.image <== model.activeImage

    HBox.setHgrow(view, Priority.Always)

    val scrollPane = new ScrollPane {
      content = view
    }

//    scrollPane.addEventFilter(ScrollEvent, )


    scene = new Scene {
      fill = Color.rgb(38, 38, 38)
      content = new VBox {
        minWidth = 1500
        children = Seq(
          menuBar,
          new HBox {
            hgrow = Priority.Always
            children = Seq(
              fileListView,
              scrollPane
            )
          }
        )
      }
      
    }

    fileMenuOpen.onAction = (e: ActionEvent) => {
      commitRefSync(ref, model, onFileMenuOpenAction)
    }

    fileMenuDirectoryOpen.onAction = (e: ActionEvent) => {
        commitRefSync(ref, model, onFileMenuDirectoryOpenAction)
    }

    fileListView.selectionModel.apply().selectedItems.onChange {
      val selectedName = fileListView.selectionModel.apply().getSelectedItem

      val fileTree: FileTree = (AppState._directoryMenu composeLens DirectoryMenu._files).get(ref.a)
      val selectedFile = CurrentFile(fileTree.files.find(f => f.getName == selectedName))

      commitRefSync(ref, model, updateCurrentFile(selectedFile))
    }
  }


  ////////// updaters

  val updateCurrentFile = (currentFile: CurrentFile) => (appRef: IORef) => {
    for {
      state <- appRef.get
      newState = AppState._currentFile.set(currentFile)(state)
    } yield newState
  }


  /////// Actions

  val onFileMenuOpenAction: IORef => IO[AppState] = appRef => {
    val fileChooser = new FileChooser
    val selectedFile = Option(fileChooser.showOpenDialog(stage))

    val currentFile = CurrentFile(for {
      f <- selectedFile
    } yield (f))

    updateCurrentFile(currentFile)(appRef)
  }

  val onFileMenuDirectoryOpenAction: IORef => IO[AppState] = appRef => {
    val dirChooser = new DirectoryChooser
    val selectedDirF: Option[File] = Option(dirChooser.showDialog(stage))

    val config = (for {
      f <- selectedDirF
      absolutePath = f.getAbsolutePath()
      files = f.listFiles()
    } yield (absolutePath, files))

    val dirPath = config match {
      case Some((dir, _)) => DirectoryPath(Some(dir))
      case None => DirectoryPath(None)
    }

    val files = config match {
      case Some((_, files)) => FileTree(files, randomUUID())
      case None => FileTree(Array.empty, randomUUID())
    }

    for {
      state <- appRef.get
      newState1 = AppState._directoryPath.set(dirPath)(state)
      newState2 = (AppState._directoryMenu composeLens DirectoryMenu._files).set(files)(newState1)
    } yield newState2
  }

  //////// Helpers

  val commitRefSync = (appRef: IORef, model: IOModel, fn: (IORef) => IO[AppState]) => {
    val run = (fn andThen appRef.set)
    run(appRef).unsafeRunSync()
    updateRefObservers(appRef, model)
  }

  val updateRefObservers = (appRef: IORef, model: IOModel) => {
    val io = for {
      state <- appRef.get
      currentFile = AppState._currentFile.get(state)
      fileTree = (AppState._directoryMenu composeLens DirectoryMenu._files).get(state)
    } yield (currentFile.file, fileTree)

    val (currentFileValue, fileTree) = io.unsafeRunSync()

    currentFileValue match {
      case Some(value: File) => {
        model.activeImage() = new Image(value.toURI.toString())
      }
      case None => {
        ()
      }
    }

    // @FILE_TREE
    if (fileTree.version != model.fileTreeVersion) {
      model.fileTree.clear()
      fileTree.files.foreach(f => {
        model.fileTree.add(f.getName)
      })
      model.fileTreeVersion = fileTree.version
    }

  }
}




//new Text {
//  text = "View"
//  style = "-fx-font: normal bold 100pt sans-serif"
//  fill = new LinearGradient(
//  endX = 0,
//  stops = Stops(Red, DarkRed))
//},
//  new Text {
//  text = "FX"
//  style = "-fx-font: italic bold 100pt sans-serif"
//  fill = new LinearGradient(
//  endX = 0,
//  stops = Stops(White, DarkGray)
//  )
//  effect = new DropShadow {
//  color = DarkGray
//  radius = 15
//  spread = 0.25
//}
//}