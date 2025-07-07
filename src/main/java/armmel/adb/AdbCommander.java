package armmel.adb;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;
import com.googlecode.lanterna.terminal.swing.SwingTerminalFrame;
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogBuilder;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import java.io.*;
import java.util.*;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import com.googlecode.lanterna.terminal.swing.SwingTerminalFontConfiguration;
import com.googlecode.lanterna.terminal.swing.TerminalEmulatorDeviceConfiguration;

public class AdbCommander {

  static Process adbShell;
  static BufferedWriter adbIn;
  static BufferedReader adbOut;

  static String[] panes = { "/sdcard", System.getProperty("user.home") };
  static String[] types = { "adb", "local" };
  static int active = 0;

  static ActionListBox pane1;
  static ActionListBox pane2;
  static MultiWindowTextGUI gui;

  public static void main(String[] args) throws Exception {
    initAdbShell();
    System.setProperty("awt.useSystemAAFontSettings", "on");
    System.setProperty("swing.aatext", "true");
    // Choose your font (must be monospaced)
    Font baseFont = new Font("Monospaced", Font.PLAIN, 14);

    // Register font if needed (optional for fallback fonts)
    GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(baseFont);

    // Create font config
    SwingTerminalFontConfiguration fontConfig = SwingTerminalFontConfiguration.newInstance(baseFont);

    // ✅ Initialize Terminal
    DefaultTerminalFactory terminalFactory = new DefaultTerminalFactory()
        .setInitialTerminalSize(new TerminalSize(80, 25)).setTerminalEmulatorFontConfiguration(fontConfig);
    Terminal terminal = terminalFactory.createTerminal();

    // ✅ Check if we're using Swing
    final SwingTerminalFrame swingFrame = (terminal instanceof SwingTerminalFrame) ? (SwingTerminalFrame) terminal
        : null;
    // ✅ Start Screen
    Screen screen = new TerminalScreen(terminal);
    screen.startScreen();

    gui = new MultiWindowTextGUI(screen);

    // ✅ Window with custom key handler
    BasicWindow window = new BasicWindow("ADB File Manager") {
      @Override
      public boolean handleInput(KeyStroke key) {
        switch (key.getKeyType()) {
          case Escape -> {
            try {
              screen.stopScreen(); // Stop Lanterna
              if (swingFrame != null) {
                swingFrame.dispose(); // Close Swing window
              }
            } catch (IOException e) {
              e.printStackTrace();
            }
            return true;
          }
          case F8 -> {
            deleteSelectedItem();
            return true;
          }
          case Tab -> {
            active = 1 - active;
            if (active == 0)
              pane1.takeFocus();
            else
              pane2.takeFocus();
            return true;
          }
          case Enter -> {
            ActionListBox activePane = (active == 0) ? pane1 : pane2;
            int index = activePane.getSelectedIndex();
            if (index >= 0) {
              String item = activePane.getItemAt(index).toString();

              String path;
              if (item.equals("..")) {
                if (types[active].equals("local")) {
                  if (panes[active].equals("::DRIVES")) {
                    return true; // already at top, stay there
                  } else {
                    File parent = new File(panes[active]).getParentFile();
                    path = (parent != null) ? parent.getAbsolutePath() : "::DRIVES";
                  }
                  System.err.println(path);
                } else { // adb
                  path = normalizeAdbPath(panes[active] + "/..");
                }
              } else {
                if (types[active].equals("local")) {
                  if (panes[active].equals("::DRIVES")) {
                    path = item; // ✅ just use "C:\", "D:\", etc. directly
                  } else {
                    path = new File(panes[active], item).getAbsolutePath();
                  }
                } else {
                  path = normalizeAdbPath(panes[active] + "/" + item);
                }
              }

              boolean allow = true;
              if (types[active].equals("local") && path.equals("::DRIVES")) {
                // trust that drive paths like C:\ are valid
              } else {
                allow = isDir(path, types[active]);
              }

              if (allow) {
                panes[active] = path;
                updatePane(activePane, path, types[active]);
              }
            }
            return true;
          }
          case F5 -> {
            copySelectedItem(); // ✅ Call your copy logic here
            return true;
          }
          default -> {
            return super.handleInput(key);
          }
        }
      }
    };

    // ✅ UI Layout
    Panel mainPanel = new Panel();
    mainPanel.setLayoutManager(new LinearLayout(Direction.HORIZONTAL));
    pane1 = new StickyActionListBox(new TerminalSize(40, 20));
    pane2 = new StickyActionListBox(new TerminalSize(40, 20));

    updatePane(pane1, panes[0], types[0]);
    updatePane(pane2, panes[1], types[1]);

    mainPanel.addComponent(pane1);
    mainPanel.addComponent(pane2);
    window.setComponent(mainPanel);
    if (swingFrame != null) {
      TerminalSize size = screen.getTerminalSize();
      int charWidth = 10; // adjust based on your font
      int charHeight = 18; // adjust based on your font

      int width = size.getColumns() * charWidth + 16; // +16 for frame border
      int height = size.getRows() * charHeight + 39; // +39 for title bar

      swingFrame.setSize(width, height);
      swingFrame.setLocationRelativeTo(null);
    }
    gui.addWindowAndWait(window);

    adbIn.write("exit\n");
    adbIn.flush();
    adbShell.destroy();
  }

  static String normalizeAdbPath(String path) {
    String[] parts = path.split("/");
    Deque<String> stack = new ArrayDeque<>();
    for (String part : parts) {
      if (part.isEmpty() || part.equals("."))
        continue;
      if (part.equals("..")) {
        if (!stack.isEmpty())
          stack.removeLast();
      } else {
        stack.addLast(part);
      }
    }
    StringBuilder result = new StringBuilder("/");
    for (String part : stack) {
      result.append(part).append("/");
    }
    if (result.length() > 1)
      result.setLength(result.length() - 1);
    return result.toString();
  }

  static boolean confirm(String title, String message) {
    return new MessageDialogBuilder()
        .setTitle(title)
        .setText(message)
        .addButton(MessageDialogButton.Yes)
        .addButton(MessageDialogButton.No)
        .build()
        .showDialog(gui) == MessageDialogButton.Yes;
  }

  static void showError(String message) {
    new MessageDialogBuilder()
        .setTitle("Error")
        .setText(message)
        .addButton(MessageDialogButton.OK)
        .build()
        .showDialog(gui);
  }

  static void deleteSelectedItem() {
    ActionListBox box = (active == 0) ? pane1 : pane2;
    int index = box.getSelectedIndex();
    if (index < 0)
      return;

    String item = box.getItemAt(index).toString();
    if (item.equals("..") || item.equals("(empty)") || item.equals("(error)"))
      return;

    String path = types[active].equals("local")
        ? new File(panes[active], item).getAbsolutePath()
        : normalizeAdbPath(panes[active] + "/" + item);

    boolean confirmed = confirm("Delete", "Are you sure you want to delete:\n" + item + "?");
    if (!confirmed)
      return;

    try {
      boolean success = false;

      if (types[active].equals("local")) {
        File f = new File(path);
        if (f.isFile())
          success = f.delete();
      } else if (types[active].equals("adb")) {
        adbIn.write("[ -f " + quote(path) + " ] && rm " + quote(path) + "\n");
        adbIn.write("echo __RM_DONE__\n");
        adbIn.flush();
        String line;
        while ((line = adbOut.readLine()) != null) {
          if (line.trim().equals("__RM_DONE__"))
            break;
        }
        success = true; // assume success
      }

      if (success) {
        updatePane(box, panes[active], types[active]);
      } else {
        showError("Failed to delete " + item);
      }

    } catch (IOException e) {
      e.printStackTrace();
      showError("Error while deleting " + item);
    }
  }

  static void initAdbShell() throws IOException {
    adbShell = new ProcessBuilder("adb", "shell").start();
    adbIn = new BufferedWriter(new OutputStreamWriter(adbShell.getOutputStream()));
    adbOut = new BufferedReader(new InputStreamReader(adbShell.getInputStream()));
    adbIn.write("echo READY\n");
    adbIn.flush();
    String line;
    while ((line = adbOut.readLine()) != null) {
      if (line.trim().equals("READY"))
        break;
    }
  }

  static void updatePane(ActionListBox box, String path, String type) {
    box.clearItems();
    List<String> items = getItems(path, type);
    for (String item : items) {
      box.addItem(item, () -> {
      }); // no-op
    }
  }

  static List<String> getItems(String path, String type) {
    List<String> list = new ArrayList<>();
    try {
      if ("adb".equals(type)) {
        adbIn.write("cd " + quote(path) + " && ls -1\n");
        adbIn.write("echo __END__\n");
        adbIn.flush();

        String line;
        while ((line = adbOut.readLine()) != null) {
          line = line.trim();
          if (line.equals("__END__"))
            break;
          if (!line.isEmpty())
            list.add(line);

        }

      } else if ("local".equals(type)) {
        if (path.equals("::DRIVES")) {
          for (File root : File.listRoots()) {
            list.add(root.getAbsolutePath());
          }
        } else {
          File f = new File(path);
          String[] files = f.list();
          if (files != null)
            list.addAll(Arrays.asList(files));
        }
      }

      list.add(0, "..");
    } catch (IOException e) {
      list.add("(error)");
      e.printStackTrace();
    }
    return list.isEmpty() ? List.of("(empty)") : list;
  }

  static boolean isRoot(String path, String type) {
    if ("adb".equals(type)) {
      return path.equals("/");
    } else {
      return path.equals("::DRIVES") || new File(path).toPath().getParent() == null;
    }
  }

  static boolean isDir(String path, String type) {
    try {
      if ("adb".equals(type)) {
        String marker = "__ISDIR_DONE__";

        adbIn.write("[ -d " + quote(path) + " ] && echo dir || echo nodir\n");
        adbIn.write("echo " + marker + "\n");
        adbIn.flush();

        boolean result = false;
        String line;

        while ((line = adbOut.readLine()) != null) {
          line = line.trim();
          if (line.equals("dir"))
            result = true;
          if (line.equals(marker))
            break; // always consume till marker
        }
        return result;

      } else if ("local".equals(type)) {
        System.out.println("masuk : " + path);
        return new File(path).isDirectory();
      }

    } catch (IOException e) {
      e.printStackTrace();
    }
    return false;
  }

  static String quote(String s) {
    return "'" + s.replace("'", "'\\''") + "'";
  }

  static void copySelectedItem() {
    ActionListBox srcPane = (active == 0) ? pane1 : pane2;
    ActionListBox dstPane = (active == 0) ? pane2 : pane1;
    int srcIndex = srcPane.getSelectedIndex();

    if (srcIndex < 0)
      return;
    String itemName = srcPane.getItemAt(srcIndex).toString();
    if (itemName.equals("..") || itemName.equals("(error)") || itemName.equals("(empty)"))
      return;

    String srcType = types[active];
    String dstType = types[1 - active];

    // Only support adb <-> local
    if (srcType.equals(dstType))
      return;

    String srcPath = srcType.equals("local")
        ? new File(panes[active], itemName).getAbsolutePath()
        : normalizeAdbPath(panes[active] + "/" + itemName);

    String dstPath = dstType.equals("local")
        ? new File(panes[1 - active], itemName).getAbsolutePath()
        : normalizeAdbPath(panes[1 - active] + "/" + itemName);
    System.out.println(srcPath + "; " + dstPath);
    try {
      if (srcType.equals("local") && dstType.equals("adb")) {
        // local → adb
        Process p = new ProcessBuilder("adb", "push", srcPath, dstPath).inheritIO().start();
        p.waitFor();
      } else if (srcType.equals("adb") && dstType.equals("local")) {
        // adb → local
        Process p = new ProcessBuilder("adb", "pull", srcPath, dstPath).inheritIO().start();
        p.waitFor();
      }

      updatePane(dstPane, panes[1 - active], dstType);

    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  static class StickyActionListBox extends ActionListBox {
    public StickyActionListBox(TerminalSize size) {
      super(size);
    }

    @Override
    public Result handleKeyStroke(KeyStroke key) {
      switch (key.getKeyType()) {
        case ArrowUp:
          if (getSelectedIndex() > 0) {
            setSelectedIndex(getSelectedIndex() - 1);
            return Result.HANDLED;
          }
          return Result.HANDLED; // block moving away
        case ArrowDown:
          if (getSelectedIndex() < getItemCount() - 1) {
            setSelectedIndex(getSelectedIndex() + 1);
            return Result.HANDLED;
          }
          return Result.HANDLED; // block moving away
        case ArrowLeft:
          return Result.HANDLED;
        case ArrowRight:
          return Result.HANDLED;
        default:
          return super.handleKeyStroke(key);
      }
    }
  }
}
