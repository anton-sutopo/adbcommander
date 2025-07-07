package armmel.adb;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;
import com.googlecode.lanterna.terminal.swing.SwingTerminalFrame;
import java.io.*;
import java.util.*;

public class AdbCommander {

  static Process adbShell;
  static BufferedWriter adbIn;
  static BufferedReader adbOut;

  static String[] panes = {"/sdcard", System.getProperty("user.home")};
  static String[] types = {"adb", "local"};
  static int active = 0;

  static ActionListBox pane1;
  static ActionListBox pane2;

  public static void main(String[] args) throws Exception {
    initAdbShell();

    // ✅ Initialize Terminal
    DefaultTerminalFactory terminalFactory =
        new DefaultTerminalFactory().setInitialTerminalSize(new TerminalSize(80, 25));
    Terminal terminal = terminalFactory.createTerminal();

    // ✅ Check if we're using Swing
    final SwingTerminalFrame swingFrame =
        (terminal instanceof SwingTerminalFrame) ? (SwingTerminalFrame) terminal : null;

    // ✅ Start Screen
    Screen screen = new TerminalScreen(terminal);
    screen.startScreen();

    MultiWindowTextGUI gui = new MultiWindowTextGUI(screen);

    // ✅ Window with custom key handler
    BasicWindow window =
        new BasicWindow("ADB File Manager") {
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
              case Tab -> {
                active = 1 - active;
                if (active == 0) pane1.takeFocus();
                else pane2.takeFocus();
                return true;
              }
              case Enter -> {
                ActionListBox activePane = (active == 0) ? pane1 : pane2;
                int index = activePane.getSelectedIndex();
                if (index >= 0) {
                  String item = activePane.getItemAt(index).toString();
                  String path =
                      types[active].equals("local")
                          ? new File(panes[active], item).getAbsolutePath()
                          : normalizeAdbPath(panes[active] + "/" + item);
                  System.out.println(path);
                  if (isDir(path, types[active])) {
                    panes[active] = path;
                    updatePane(activePane, path, types[active]);
                  }
                }
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
      if (part.isEmpty() || part.equals(".")) continue;
      if (part.equals("..")) {
        if (!stack.isEmpty()) stack.removeLast();
      } else {
        stack.addLast(part);
      }
    }
    StringBuilder result = new StringBuilder("/");
    for (String part : stack) {
      result.append(part).append("/");
    }
    if (result.length() > 1) result.setLength(result.length() - 1);
    return result.toString();
  }

  static void initAdbShell() throws IOException {
    adbShell = new ProcessBuilder("adb", "shell").start();
    adbIn = new BufferedWriter(new OutputStreamWriter(adbShell.getOutputStream()));
    adbOut = new BufferedReader(new InputStreamReader(adbShell.getInputStream()));
    adbIn.write("echo READY\n");
    adbIn.flush();
    String line;
    while ((line = adbOut.readLine()) != null) {
      if (line.trim().equals("READY")) break;
    }
  }

  static void updatePane(ActionListBox box, String path, String type) {
    box.clearItems();
    List<String> items = getItems(path, type);
    for (String item : items) {
      box.addItem(item, () -> {}); // no-op
    }
  }

  static List<String> getItems(String path, String type) {
    List<String> list = new ArrayList<>();
    try {
      if ("adb".equals(type)) {
        // Send command with a clear sentinel
        adbIn.write("cd " + quote(path) + " && ls -1\n");
        adbIn.write("echo __END__\n"); // sentinel
        adbIn.flush();

        String line;
        while ((line = adbOut.readLine()) != null) {
          line = line.trim();
          if (line.equals("__END__")) break;
          if (!line.isEmpty()) {
            list.add(line);
          }
        }

      } else if ("local".equals(type)) {
        File f = new File(path);
        String[] files = f.list();
        if (files != null) list.addAll(Arrays.asList(files));
      }
      if (!isRoot(path, type)) {
        list.add(0, "..");
      }
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
      return new File(path).toPath().getParent() == null;
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
          if (line.equals("dir")) result = true;
          if (line.equals(marker)) break; // always consume till marker
        }
        return result;

      } else if ("local".equals(type)) {
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
        default:
          return super.handleKeyStroke(key);
      }
    }
  }
}
