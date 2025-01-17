package codes.seanhenry.helpers;

import com.intellij.ide.IdeView;
import com.intellij.ide.highlighter.ProjectFileType;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.idea.IdeaTestApplication;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataProvider;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.impl.FileTypeManagerImpl;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ex.ProjectManagerEx;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.encoding.EncodingManager;
import com.intellij.psi.*;
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageManagerImpl;
import com.intellij.testFramework.*;
import com.intellij.testFramework.builders.ModuleFixtureBuilder;
import com.intellij.testFramework.fixtures.HeavyIdeaTestFixture;
import com.intellij.testFramework.fixtures.impl.BaseFixture;
import com.intellij.util.PathUtil;
import com.intellij.util.SmartList;
import com.intellij.util.lang.CompoundRuntimeException;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.Assert;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Creates new project for each test.
 * @author mike
 */
@SuppressWarnings("TestOnlyProblems")
public class MyHeavyIdeaTestFixtureImpl extends BaseFixture implements HeavyIdeaTestFixture {
  private Project myProject;
  private final Set<File> myFilesToDelete = new HashSet<>();
  private IdeaTestApplication myApplication;
  private final Set<ModuleFixtureBuilder> myModuleFixtureBuilders = new LinkedHashSet<>();
  private EditorListenerTracker myEditorListenerTracker;
  private ThreadTracker myThreadTracker;
  private final String myName;
  private final String myProjectDir;

  public MyHeavyIdeaTestFixtureImpl(@NotNull String name, @NotNull String projectDir) {
    myName = name;
    myProjectDir = projectDir;
  }

  protected void addModuleFixtureBuilder(ModuleFixtureBuilder builder) {
    myModuleFixtureBuilders.add(builder);
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();

    initApplication();
    setUpProject();

    EncodingManager.getInstance(); // adds listeners
    myEditorListenerTracker = new EditorListenerTracker();
    myThreadTracker = new ThreadTracker();
    InjectedLanguageManagerImpl.pushInjectors(getProject());
  }

  @Override
  public void tearDown() throws Exception {
    final Project project = getProject();
    final List<Throwable> exceptions = new SmartList<>();
    try {
      LightPlatformTestCase.doTearDown(project, myApplication, false, exceptions);

      for (ModuleFixtureBuilder moduleFixtureBuilder : myModuleFixtureBuilders) {
        moduleFixtureBuilder.getFixture().tearDown();
      }

      //EdtTestUtil.runInEdtAndWait(() -> PlatformTestCase.closeAndDisposeProjectAndCheckThatNoOpenProjects(project, exceptions));
      myProject = null;

      for (File fileToDelete : myFilesToDelete) {
        if (!FileUtil.delete(fileToDelete)) {
          exceptions.add(new IOException("Can't delete " + fileToDelete));
        }
      }
    }
    catch (Throwable e) {
      exceptions.add(e);
    }

    try {
      super.tearDown();
    }
    catch (Throwable e) {
      exceptions.add(e);
    }

    try {
      //myEditorListenerTracker.checkListenersLeak();
      //myThreadTracker.checkLeak();
      LightPlatformTestCase.checkEditorsReleased(exceptions);
      PlatformTestCase.cleanupApplicationCaches(project);
      InjectedLanguageManagerImpl.checkInjectorsAreDisposed(project);
    }
    finally {
      CompoundRuntimeException.throwIfNotEmpty(exceptions);
    }
  }

  private void setUpProject() throws IOException {
    File tempDirectory = FileUtil.createTempDirectory(myName, "");
    PlatformTestCase.synchronizeTempDirVfs(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tempDirectory));
    myFilesToDelete.add(tempDirectory);

    String projectPath = FileUtil.toSystemIndependentName(tempDirectory.getPath()) + "/" + myName + ProjectFileType.DOT_DEFAULT_EXTENSION;
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    new Throwable(projectPath).printStackTrace(new PrintStream(buffer));
    //myProject = PlatformTestCase.createProject(projectPath, buffer.toString());
    myProject = ProjectUtil.openOrImport(myProjectDir, null, true);

    EdtTestUtil.runInEdtAndWait(() -> {
      ProjectManagerEx.getInstanceEx().openTestProject(myProject);

      for (ModuleFixtureBuilder moduleFixtureBuilder : myModuleFixtureBuilders) {
        moduleFixtureBuilder.getFixture().setUp();
      }

      LightPlatformTestCase.clearUncommittedDocuments(myProject);
      ((FileTypeManagerImpl)FileTypeManager.getInstance()).drainReDetectQueue();
    });
  }

  private void initApplication() {
    myApplication = IdeaTestApplication.getInstance();
    myApplication.setDataProvider(new MyDataProvider());
  }

  @Override
  public Project getProject() {
    Assert.assertNotNull("setUp() should be called first", myProject);
    return myProject;
  }

  @Override
  public Module getModule() {
    Module[] modules = ModuleManager.getInstance(getProject()).getModules();
    return modules.length == 0 ? null : modules[0];
  }

  private class MyDataProvider implements DataProvider {
    @Override
    @Nullable
    public Object getData(@NonNls String dataId) {
      if (CommonDataKeys.PROJECT.is(dataId)) {
        return myProject;
      }
      else if (CommonDataKeys.EDITOR.is(dataId) || OpenFileDescriptor.NAVIGATE_IN_EDITOR.is(dataId)) {
        if (myProject == null) return null;
        return FileEditorManager.getInstance(myProject).getSelectedTextEditor();
      }
      else {
        Editor editor = (Editor)getData(CommonDataKeys.EDITOR.getName());
        if (editor != null) {
          FileEditorManagerEx manager = FileEditorManagerEx.getInstanceEx(myProject);
          return manager.getData(dataId, editor, editor.getCaretModel().getCurrentCaret());
        }
        else if (LangDataKeys.IDE_VIEW.is(dataId)) {
          VirtualFile[] contentRoots = ProjectRootManager.getInstance(myProject).getContentRoots();
          final PsiDirectory psiDirectory = PsiManager.getInstance(myProject).findDirectory(contentRoots[0]);
          if (contentRoots.length > 0) {
            return new IdeView() {
              @Override
              public void selectElement(PsiElement element) {

              }

              @NotNull
              @Override
              public PsiDirectory[] getDirectories() {
                return new PsiDirectory[] {psiDirectory};
              }

              @Override
              public PsiDirectory getOrChooseDirectory() {
                return psiDirectory;
              }
            };
          }
        }
        return null;
      }
    }
  }

  @Override
  public PsiFile addFileToProject(@NotNull @NonNls String rootPath, @NotNull @NonNls final String relativePath, @NotNull @NonNls final String fileText) throws IOException {
    final VirtualFile dir = VfsUtil.createDirectories(rootPath + "/" + PathUtil.getParentPath(relativePath));

    final VirtualFile[] virtualFile = new VirtualFile[1];
    new WriteCommandAction.Simple(getProject()) {
      @Override
      protected void run() throws Throwable {
        virtualFile[0] = dir.createChildData(this, StringUtil.getShortName(relativePath, '/'));
        VfsUtil.saveText(virtualFile[0], fileText);
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
      }
    }.execute();
    return ApplicationManager.getApplication().runReadAction(new Computable<PsiFile>() {
      @Override
      public PsiFile compute() {
        return PsiManager.getInstance(getProject()).findFile(virtualFile[0]);
      }
    });
  }
}

/*
@SuppressWarnings("TestOnlyProblems")
class MyHeavyIdeaTestFixtureImpl extends BaseFixture implements HeavyIdeaTestFixture {
  private Project myProject;
  private final Set<File> myFilesToDelete = new HashSet<>();
  private IdeaTestApplication myApplication;
  private final Set<ModuleFixtureBuilder> myModuleFixtureBuilders = new LinkedHashSet<>();
  private EditorListenerTracker myEditorListenerTracker;
  private ThreadTracker myThreadTracker;
  private final String myName;
  private final String myProjectDir;

  public MyHeavyIdeaTestFixtureImpl(@NotNull String name, @NotNull String projectDir) {
    myName = name;
    myProjectDir = projectDir;
  }

  protected void addModuleFixtureBuilder(ModuleFixtureBuilder builder) {
    myModuleFixtureBuilders.add(builder);
  }



  @Override
  public void setUp() throws Exception {
    super.setUp();

    initApplication();
    setUpProject();

    EncodingManager.getInstance(); // adds listeners
    myEditorListenerTracker = new EditorListenerTracker();
    myThreadTracker = new ThreadTracker();
    InjectedLanguageManagerImpl.pushInjectors(getProject());
  }

  @Override
  public void tearDown() throws Exception {
    final Project project = getProject();

    RunAll runAll = new RunAll()
      .append(() -> LightPlatformTestCase.doTearDown(project, myApplication, false))
      .append(() -> {
        for (ModuleFixtureBuilder moduleFixtureBuilder : myModuleFixtureBuilders) {
          moduleFixtureBuilder.getFixture().tearDown();
        }
      })
      //.append(() -> EdtTestUtil.runInEdtAndWait(() -> PlatformTestCase.closeAndDisposeProjectAndCheckThatNoOpenProjects(project)))
      .append(() -> closeProject())
      .append(() -> myProject = null);

    for (File fileToDelete : myFilesToDelete) {
      runAll = runAll.append(() -> {
        if (!FileUtil.delete(fileToDelete)) {
          throw new IOException("Can't delete " + fileToDelete);
        }
      });
    }

    runAll
      .append(super::tearDown)
      //.append(() -> myEditorListenerTracker.checkListenersLeak())
      //.append(() -> myThreadTracker.checkLeak())
      .append(() -> LightPlatformTestCase.checkEditorsReleased())
      .append(() -> PlatformTestCase.cleanupApplicationCaches(project))
      .append(() -> InjectedLanguageManagerImpl.checkInjectorsAreDisposed(project))
      .run();
  }

  private void setUpProject() throws IOException {
    File tempDirectory = FileUtil.createTempDirectory(myName, "");
    PlatformTestCase
      .synchronizeTempDirVfs(ObjectUtils.assertNotNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tempDirectory)));
    myFilesToDelete.add(tempDirectory);

    String projectPath = FileUtil.toSystemIndependentName(tempDirectory.getPath()) + "/" + myName + ProjectFileType.DOT_DEFAULT_EXTENSION;
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    new Throwable(projectPath).printStackTrace(new PrintStream(buffer));
    myProject = ProjectUtil.openOrImport(myProjectDir, null, true);

    EdtTestUtil.runInEdtAndWait(() -> {
      ProjectManagerEx.getInstanceEx().openTestProject(myProject);

      for (ModuleFixtureBuilder moduleFixtureBuilder : myModuleFixtureBuilders) {
        moduleFixtureBuilder.getFixture().setUp();
      }

      LightPlatformTestCase.clearUncommittedDocuments(myProject);
      ((FileTypeManagerImpl)FileTypeManager.getInstance()).drainReDetectQueue();
    });
  }

  private void initApplication() {
    myApplication = IdeaTestApplication.getInstance();
    myApplication.setDataProvider(new MyHeavyIdeaTestFixtureImpl.MyDataProvider());
  }

  @Override
  public Project getProject() {
    Assert.assertNotNull("setUp() should be called first", myProject);
    return myProject;
  }

  @Override
  public Module getModule() {
    Module[] modules = ModuleManager.getInstance(getProject()).getModules();
    return modules.length == 0 ? null : modules[0];
  }

  private class MyDataProvider implements DataProvider {
    @Override
    @Nullable
    public Object getData(@NonNls String dataId) {
      if (CommonDataKeys.PROJECT.is(dataId)) {
        return myProject;
      }
      else if (CommonDataKeys.EDITOR.is(dataId) || OpenFileDescriptor.NAVIGATE_IN_EDITOR.is(dataId)) {
        if (myProject == null) return null;
        return FileEditorManager.getInstance(myProject).getSelectedTextEditor();
      }
      else {
        Editor editor = (Editor)getData(CommonDataKeys.EDITOR.getName());
        if (editor != null) {
          FileEditorManagerEx manager = FileEditorManagerEx.getInstanceEx(myProject);
          return manager.getData(dataId, editor, editor.getCaretModel().getCurrentCaret());
        }
        else if (LangDataKeys.IDE_VIEW.is(dataId)) {
          VirtualFile[] contentRoots = ProjectRootManager.getInstance(myProject).getContentRoots();
          final PsiDirectory psiDirectory = PsiManager.getInstance(myProject).findDirectory(contentRoots[0]);
          if (contentRoots.length > 0) {
            return new IdeView() {
              @Override
              public void selectElement(PsiElement element) {

              }

              @NotNull
              @Override
              public PsiDirectory[] getDirectories() {
                return new PsiDirectory[] {psiDirectory};
              }

              @Override
              public PsiDirectory getOrChooseDirectory() {
                return psiDirectory;
              }
            };
          }
        }
        return null;
      }
    }
  }

  @Override
  public PsiFile addFileToProject(@NotNull @NonNls String rootPath, @NotNull @NonNls final String relativePath, @NotNull @NonNls final String fileText) throws IOException {
    final VirtualFile dir = VfsUtil.createDirectories(rootPath + "/" + PathUtil.getParentPath(relativePath));

    final VirtualFile[] virtualFile = new VirtualFile[1];
    new WriteCommandAction.Simple(getProject()) {
      @Override
      protected void run() throws Throwable {
        virtualFile[0] = dir.createChildData(this, StringUtil.getShortName(relativePath, '/'));
        VfsUtil.saveText(virtualFile[0], fileText);
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
      }
    }.execute();
    return ReadAction.compute(() -> PsiManager.getInstance(getProject()).findFile(virtualFile[0]));
  }

  private void closeProject() {
    ProjectManagerEx.getInstanceEx().closeTestProject(getProject());
    WriteAction.run(() -> Disposer.dispose(getProject()));
  }
}


 */
