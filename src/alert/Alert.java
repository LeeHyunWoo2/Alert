package alert;

import javax.swing.*;

import org.pcap4j.core.PcapNetworkInterface;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.io.InputStream;



public class Alert {

  private static JFrame frame; // 메인 GUI 창
  private static JTextArea textBox; // 네트워크 상태 표시
  private static JLabel uploadTag; // 업로드 트래픽 정보 표시
  private static JLabel downloadTag; // 다운로드 트래픽 정보 표시
  private static JLabel uploadValue; // 업로드 트래픽 값을 표시
  private static JLabel downloadValue; // 다운로드 트래픽 값 표시
  private static final ArrayList<PcapNetworkInterface> deviceList = new ArrayList<>(); // 네트워크 어댑터 목록
  private static JComboBox<PcapNetworkInterface> selectAdapter; // 네트워크 어댑터를 선택박스
  private static JTextField portSetting; // 포트 번호를 입력받는 곳
  private static JTextField intervalSetting; // 갱신 간격 입력받는 곳 (ms)
  private static JTextField limitSetting; // 트래픽 임계값 입력받는 곳
  private static JRadioButton selectUpload; // 알람을 활성화 선택지 라디오 버튼 1
  private static JRadioButton selectDownload; // 2
  private static JRadioButton selectDisable; // 3
  private static JButton startButton; // 모니터링 시작/정지 버튼
  private static JPanel panel1; // 패널1
  private static JPanel panel2; // 패널2
  private static Font customFont; // 커스텀 폰트
  private static final PacketCapture packetCapture = new PacketCapture(); // 클래스 수준에서 단일 인스턴스 생성


  public static void main(String[] args) {
    JTextArea textArea = new JTextArea();
    PacketCapture.setTextBox(textArea);

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      packetCapture.shutdown();
    }));

    try {
      // LookAndFeel은 프로그램을 실행하는 OS 의 기본 UI디자인을 따라간다고 함
      UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
    } catch (Exception e) {
      e.printStackTrace();
    }

    loadCustomFont();

    SwingUtilities.invokeLater(new Runnable() {
      @Override
      public void run() {
        createAndShowGUI();
        PacketCapture.NetworkAdapter(selectAdapter, deviceList);
      }
    });
  }

  private static void loadCustomFont() {
    try {
      // 리소스에서 폰트 파일을 로드
      InputStream is = Alert.class.getResourceAsStream("/MaplestoryBold.ttf");
      customFont = Font.createFont(Font.TRUETYPE_FONT, is);

      // 폰트 환경에 등록
      GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
      ge.registerFont(customFont);

      // 기본 폰트 크기 및 스타일 설정
      customFont = customFont.deriveFont(Font.BOLD, 20f);
    } catch (Exception e) {
      e.printStackTrace();
      // 폰트 로드 실패 시 기본 폰트로 대체
      customFont = new Font("Malgun Gothic", Font.BOLD, 20);
    }
  }

  private static void createAndShowGUI() { // 관례적으로 많이 쓰이는 메서드명이라고 함
    // 메인 GUI 생성, 각종 인터페이스 요소 생성 및 초기화, 시스템 트레이 아이콘 생성
    frame = new JFrame("선생님 감시 감지기");
    frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
    frame.setSize(600, 600);

    panel2 = new JPanel();
    panel2.setLayout(new BoxLayout(panel2, BoxLayout.Y_AXIS));
    panel2.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
    frame.getContentPane().add(panel2);

    initialize(panel2);
    addComponents(panel2);

    PacketCapture.setTextBox(textBox);
    startButton.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        int interval = Integer.parseInt(intervalSetting.getText());
        if (packetCapture.isRunning()) {
          PacketCapture.appendText("종료버튼 입력됨");
          packetCapture.stopMonitoring();
          startButton.setText("시작");
        } else {
          try {
            if (interval > 100 && interval < 3000) { // 갱신주기 입력값이 100~3000이면 시작
              PacketCapture.appendText("시작버튼 입력됨");
              packetCapture.startMonitoring(selectAdapter, portSetting, intervalSetting, limitSetting,
                  selectUpload, selectDownload, uploadValue, downloadValue);
              startButton.setText("정지");
            } else {
              PacketCapture.appendText("갱신주기는 100ms 이상, 3000ms 이하로 설정해야 합니다.");
            }
          } catch (NumberFormatException ex) {
            PacketCapture.appendText("숫자가 입력되지 않았거나, 숫자 이외의 값이 입력됐습니다.");
          }
        }
      }
    });

    frame.addWindowListener(new WindowAdapter() {
      @Override
      public void windowClosing(WindowEvent e) {
        frame.setVisible(false);
      }
    });

    frame.setVisible(true);

    Popup popup = new Popup();
    popup.setupSystemTray(frame);
  }

  private static void initialize(JPanel panel) {
    // GUI의 각 요소를 생성 후 초기화하는 메서드
    // 이거 만들면서 알게된 사실인데 initialize는 처음 생성 후 초기화할때 쓰는 말
    // reset은 한번 사용 후 초기화할때 쓰는 말이라고 함

    loadCustomFont();

    uploadTag = new JLabel("업로드 트래픽 ");
    uploadTag.setHorizontalAlignment(SwingConstants.RIGHT);
    uploadTag.setFont(customFont);
    uploadTag.setForeground(Color.BLUE);

    uploadValue = new JLabel("미접속");
    uploadValue.setFont(customFont);
    uploadValue.setForeground(Color.BLACK);

    downloadTag = new JLabel("다운로드 트래픽 ");
    downloadTag.setHorizontalAlignment(SwingConstants.RIGHT);
    downloadTag.setFont(customFont);
    downloadTag.setForeground(new Color(204, 0, 0));

    downloadValue = new JLabel("미접속");
    downloadValue.setFont(customFont);
    downloadValue.setForeground(Color.BLACK);

    textBox = new JTextArea(10, 40);
    textBox.setEditable(false);
  }

  private static void addComponents(JPanel panel) {
    // 인터페이스에 넣을 각종 요소들 추가하는 메서드

    JLabel adapterLabel = new JLabel("네트워크 어댑터:");
    adapterLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
    panel.add(adapterLabel);

    selectAdapter = new JComboBox<>();
    selectAdapter.setRenderer(new CustomList());
    panel.add(selectAdapter);

    JLabel portLabel = new JLabel("포트 번호:");
    portLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
    panel.add(portLabel);

    portSetting = new JTextField("11100", 20);
    panel.add(portSetting);

    JLabel intervalLabel = new JLabel("새로고침 주기(ms):");
    intervalLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
    panel.add(intervalLabel);

    intervalSetting = new JTextField("1000", 20);
    panel.add(intervalSetting);

    JLabel thresholdLabel = new JLabel("트래픽 임계점:");
    thresholdLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
    limitSetting = new JTextField("1000000", 20);
    panel.add(thresholdLabel);
    panel.add(limitSetting);

    ButtonGroup alertGroup = new ButtonGroup();

    JLabel label = new JLabel("알람 활성화 대상:");
    label.setAlignmentX(Component.CENTER_ALIGNMENT);
    panel.add(label);

    panel1 = new JPanel();
    panel2.add(panel1);
    selectUpload = new JRadioButton("업로드   ");
    panel1.add(selectUpload);
    selectUpload.setAlignmentX(Component.CENTER_ALIGNMENT);

    alertGroup.add(selectUpload);
    selectDownload = new JRadioButton("다운로드");
    panel1.add(selectDownload);
    selectDownload.setAlignmentX(Component.CENTER_ALIGNMENT);
    alertGroup.add(selectDownload);
    selectDisable = new JRadioButton("알람 비활성", true);
    panel1.add(selectDisable);
    selectDisable.setAlignmentX(Component.CENTER_ALIGNMENT);
    alertGroup.add(selectDisable);

    startButton = new JButton("시작");
    startButton.setAlignmentX(Component.CENTER_ALIGNMENT);
    panel.add(startButton);

    JPanel uploadPanel = new JPanel();
    uploadPanel.setLayout(new GridLayout(0, 2, 0, 0));
    uploadPanel.add(uploadTag);
    uploadPanel.add(uploadValue);

    panel.add(uploadPanel);

    JPanel downloadPanel = new JPanel();
    downloadPanel.setLayout(new GridLayout(0, 2, 0, 0));
    downloadPanel.add(downloadTag);
    downloadPanel.add(downloadValue);

    panel.add(downloadPanel);

    JScrollPane scrollPane = new JScrollPane(textBox);
    panel.add(scrollPane);
  }

  static class CustomList extends JLabel implements ListCellRenderer<PcapNetworkInterface> {

    public CustomList() {
      setOpaque(true);
      setVerticalAlignment(SwingConstants.TOP);
      setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0)); // 상단과 하단에 2픽셀 간격 추가
    }

    @Override
    public Component getListCellRendererComponent(JList<? extends PcapNetworkInterface> list, PcapNetworkInterface value, int index,
                                                  // 네트워크 어댑터에 장치나 어댑터 둘중 하나만 적혀있으면 허전해서 두개 다 뜨게 하는 메서드
                                                  boolean isSelected, boolean cellHasFocus) {
      if (value != null) {
        String description = value.getDescription(); // 장치명
        String name = value.getName(); // 어댑터명

        setText("<html>장치&nbsp;&nbsp; : " + description + "<br/>어댑터 : " + name + "</html>");

        if (isSelected) {
          setBackground(list.getSelectionBackground());
          setForeground(list.getSelectionForeground());
        } else {
          setBackground(list.getBackground());
          setForeground(list.getForeground());
        }
      }
      return this;
    }

  }

}
