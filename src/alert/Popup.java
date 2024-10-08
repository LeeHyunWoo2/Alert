package alert;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.net.URL;

public class Popup {

  private JFrame alertFrame; // 팝업 창
  private JLabel gifLabel; // gif 표시
  private JLabel messageLabel; // 팝업 창 하단 메시지
  private boolean isVisible = false; // 팝업의 가시성을 추적하는 플래그

  public Popup() {
    createPopupWindow();
  }

  private void createPopupWindow() {
    alertFrame = new JFrame("트래픽 경고");
    alertFrame.setSize(375, 450);
    alertFrame.setUndecorated(true);
    alertFrame.setAlwaysOnTop(true);
    alertFrame.setLocationRelativeTo(null);
    alertFrame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

    alertFrame.setLayout(new BorderLayout());

    URL gifUrl = Alert.class.getResource("/jerry.gif");
    if (gifUrl != null) {
      gifLabel = new JLabel(new ImageIcon(gifUrl));
    } else {
      gifLabel = new JLabel("createPopupWindow");
    }
    gifLabel.setHorizontalAlignment(JLabel.CENTER);
    alertFrame.add(gifLabel, BorderLayout.CENTER);

    messageLabel = new JLabel("");
    messageLabel.setFont(new Font("Malgun Gothic", Font.BOLD, 24));
    messageLabel.setHorizontalAlignment(JLabel.CENTER);
    alertFrame.add(messageLabel, BorderLayout.SOUTH);
  }

  public void setupSystemTray(JFrame frame) {
    if (SystemTray.isSupported()) {
      SystemTray tray = SystemTray.getSystemTray();
      Image image = Toolkit.getDefaultToolkit().createImage(Alert.class.getResource("/icon.png"));
      TrayIcon trayIcon = new TrayIcon(image, "트래픽 모니터");

      PopupMenu popup = new PopupMenu();
      MenuItem exitItem = new MenuItem("종료");

      exitItem.addActionListener(new ActionListener() {
        public void actionPerformed(ActionEvent e) {
          SystemTray.getSystemTray().remove(trayIcon);
          System.exit(0);
        }
      });
      popup.add(exitItem);

      trayIcon.setPopupMenu(popup);
      trayIcon.setImageAutoSize(true);
      trayIcon.setToolTip("트래픽 모니터");

      try {
        tray.add(trayIcon);
      } catch (AWTException e) {
        e.printStackTrace();
      }

      trayIcon.addActionListener(new ActionListener() {
        @Override
        public void actionPerformed(ActionEvent e) {
          SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
              frame.setVisible(true);
            }
          });
        }
      });
    }
  }

  public void showPopup() {
    SwingUtilities.invokeLater(new Runnable() {
      @Override
      public void run() {
        if (alertFrame == null) {
          createPopupWindow();
        }
        alertFrame.setVisible(true);
        isVisible = true; // 팝업이 열릴 때 플래그 설정
      }
    });
  }

  public void hidePopup() {
    SwingUtilities.invokeLater(new Runnable() {
      @Override
      public void run() {
        if (alertFrame != null) {
          alertFrame.setVisible(false);
          isVisible = false; // 팝업이 닫힐 때 플래그 해제
        }
      }
    });
  }

  public boolean isVisible() {
    return isVisible; // 팝업의 가시성을 반환
  }
}
