package alert;

import org.pcap4j.core.PcapHandle;
import org.pcap4j.core.PcapNativeException;
import org.pcap4j.core.PcapNetworkInterface;
import org.pcap4j.core.PcapNetworkInterface.PromiscuousMode;
import org.pcap4j.core.Pcaps;
import org.pcap4j.packet.Packet;
import org.pcap4j.packet.TcpPacket;
import org.pcap4j.packet.IpV4Packet;
import org.pcap4j.packet.IpV6Packet;
import org.pcap4j.core.NotOpenException;
import org.pcap4j.core.PacketListener;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class PacketCapture {

  private static PcapHandle currentPcap = null;
  private static JTextArea textBox;
  private ScheduledExecutorService scheduler; // 스케줄러 인스턴스를 필드로 선언
  private ExecutorService executorService; // 스레드 풀도 마찬가지로 필요시 새로 생성하게끔 셋팅
  private static final Popup popup = new Popup();

  private int threshold;
  private int interval;
  private int port;

  private JLabel uploadLabel; // 업로드 라벨
  private JLabel downloadLabel; // 다운로드 라벨
  private boolean alertOnUpload; // 업로드 알림
  private boolean alertOnDownload; // 다운로드 알림
  private long lastPopupCloseTime; // 마지막 팝업 닫힌 시간 기록
  private long lastUploadDisconnectedTime;
  private long lastDownloadDisconnectedTime;
  private final ConcurrentLinkedQueue<Packet> firstBuffer = new ConcurrentLinkedQueue<>(); // 첫번째 버퍼
  private final ConcurrentLinkedQueue<Packet> secondBuffer = new ConcurrentLinkedQueue<>(); // 두번째 버퍼
  private final AtomicBoolean usingFirstBuffer = new AtomicBoolean(true); // 현재 사용중인 버퍼 플래그
  private volatile boolean isRunning = false;
  private ScheduledFuture<?> scheduledTask;
  private double smoothedUploadTraffic = 0;
  private double smoothedDownloadTraffic = 0;
  private final double alpha = 0.6; // EMA 가중치 (0 < alpha < 1)


  public static void setTextBox(JTextArea textBox) {
    PacketCapture.textBox = textBox;
  }

  public boolean isRunning() {
    appendText("isRunning() 호출됨, 결과: " + isRunning);
    return isRunning;
  }

  public static void NetworkAdapter(JComboBox<PcapNetworkInterface> selectAdapter, ArrayList<PcapNetworkInterface> deviceList) {
    try {
      List<PcapNetworkInterface> allDevs = Pcaps.findAllDevs();
      if (allDevs.isEmpty()) {
        // appendText("네트워크 어댑터 목록 비어 있음");
        return;
      }

      for (PcapNetworkInterface device : allDevs) {
        selectAdapter.addItem(device);
        //// appendText("네트워크 어댑터 추가됨: " + device.getName());
      }
    } catch (PcapNativeException e) {
      // appendText("네트워크 어댑터 검색 실패: " + e.getMessage());
    }
  }

  public void startMonitoring(JComboBox<PcapNetworkInterface> selectAdapter, JTextField portSetting, JTextField intervalSetting,
                              JTextField limitSetting, JRadioButton selectUpload, JRadioButton selectDownload, JLabel uploadValue,
                              JLabel downloadValue) {
    // 스케줄러가 null 이거나 이미 종료되었다면 새로운 인스턴스 생성
    if(scheduler == null || scheduler.isShutdown()){
      scheduler = Executors.newScheduledThreadPool(1);
    }
    if(executorService == null || executorService.isShutdown()){
      executorService = Executors.newFixedThreadPool(1);
    }

    // 초기 설정 작업
    PcapNetworkInterface selectedDevice = (PcapNetworkInterface) selectAdapter.getSelectedItem();
    port = Integer.parseInt(portSetting.getText());
    interval = Integer.parseInt(intervalSetting.getText());
    threshold = Integer.parseInt(limitSetting.getText());
    uploadLabel = uploadValue;
    downloadLabel = downloadValue;
    alertOnUpload = selectUpload.isSelected();
    alertOnDownload = selectDownload.isSelected();
    isRunning = true; // 모니터링 상태 플래그 설정

    // 패킷 캡처를 별도의 스레드에서 실행
    executorService.submit(() -> {
      try {
        captureNetworkTraffic(selectedDevice); // 패킷 캡처 시작
      } catch (InterruptedException e) {
        // 예외 처리
      }
    });

    // 패킷 분석 스케줄러 시작
    scheduler.scheduleAtFixedRate(this::switchBufferAndProcess, 0, interval, TimeUnit.MILLISECONDS);
  }

  private void captureNetworkTraffic(PcapNetworkInterface selectedDevice) throws InterruptedException {
    if (!isRunning) {
      return; // 정지 상태일 경우 메서드를 종료
    }
    // appendText("captureNetworkTraffic 호출됨");
    appendText(String.format("\n장치: %s\n어댑터: %s\n포트: %d, 임계값 설정: %d bytes", selectedDevice.getDescription(),
        selectedDevice.getName(), port, threshold));
    int snaplen = 32 * 1024 * 1024; // 버퍼 32MB 할당
    int timeout = 10 * 1000;

    try {
      currentPcap = selectedDevice.openLive(snaplen, PromiscuousMode.PROMISCUOUS, timeout);
      appendText("패킷 캡처 시작");
      currentPcap.setBlockingMode(PcapHandle.BlockingMode.NONBLOCKING);
      // 논블로킹 모드, 패킷이 없을경우 즉시 반환하여 뻘짓 안하게 만듦.

      PacketListener listener = packet -> {
        // appendText("패킷 수신됨: " + packet.length());
        if (usingFirstBuffer.get()) { // 현재 사용중인 버퍼에 저장
          firstBuffer.offer(packet);
        } else {
          secondBuffer.offer(packet);
        }
      };

      while (isRunning) {
        // appendText("패킷 캡처 루프 동작 중");
        // 루프보다 리스너가 더 위에 있는 이유 : 리스너는 콜백 메서드임.
        // 그리고 루프는 실제 캡처를 담당하기 때문에 이걸 처리해줄 리스너가 미리 정의가 되어있어야함
        // 미세한 타이밍 차이로 문제가 발생할수도 있기 때문임
        try {
          currentPcap.loop(1, listener);
          // 패킷이 없으면 빠르게 반환하도록 논블로킹 모드로 패킷 캡처 시도
        } catch (NotOpenException e) {
          appendText("패킷 캡처 오류 발생 : " + e.getMessage());
          break;
        }
      }
    } catch (PcapNativeException | NotOpenException e) {
       appendText("패킷 캡처를 시작할 수 없음: " + e.getMessage());
    }
  }

  // 버퍼를 전환하고, 이전 버퍼를 처리하는 메서드
  public void switchBufferAndProcess() {
    if (!isRunning) {
      return; // 정지 상태일 경우 메서드를 종료
    }
    ConcurrentLinkedQueue<Packet> bufferToProcess;

    usingFirstBuffer.set(!usingFirstBuffer.get()); // 현재 사용중인 버퍼 전환
    bufferToProcess = usingFirstBuffer.get() ? secondBuffer : firstBuffer; // 이전 버퍼 선택

    int totalUploadTraffic = 0;
    int totalDownloadTraffic = 0;

    for (Packet packet : bufferToProcess) {
      int[] traffic = processPacket(packet); // 패킷 처리 후, 결과 트래픽 반환
      totalUploadTraffic += traffic[0]; // 업로드 트래픽 누적
      totalDownloadTraffic += traffic[1]; // 다운로드 트래픽 누적
    }
    updateTrafficValues(totalUploadTraffic, totalDownloadTraffic); // 누적된 값을 보냄

    bufferToProcess.clear(); // 처리 완료 후 버퍼 비움
  }

  // 패킷을 처리하는 메서드
  public int[] processPacket(Packet packet) {
    if (!isRunning) {
      return new int[]{0, 0};
    }
    int uploadTraffic = 0;
    int downloadTraffic = 0;
    try {
      // 패킷을 검사하고 처리
      TcpPacket tcpPacket = packet.get(TcpPacket.class);
      IpV4Packet ipV4Packet = packet.get(IpV4Packet.class);
      IpV6Packet ipV6Packet = packet.get(IpV6Packet.class);

      if (tcpPacket != null && (ipV4Packet != null || ipV6Packet != null)) {
        int srcPort = tcpPacket.getHeader().getSrcPort().valueAsInt();
        int dstPort = tcpPacket.getHeader().getDstPort().valueAsInt();
        int payloadLength = tcpPacket.getPayload() != null ? tcpPacket.getPayload().length() : 0;
        // appendText("Payload length: " + payloadLength);

        if (payloadLength > 0) {
          // 포트 값이 예상과 다른 경우, 로그를 통해 확인
          if (dstPort == port) {
            downloadTraffic = payloadLength;
            // 패킷 수를 안전하게 증가시키기 위해 원자적 타입을 유지(.addAndGet)
            // appendText("Download traffic updated: " + downloadTraffic);
          } else if (srcPort == port) {
            uploadTraffic = payloadLength;
            // appendText("Upload traffic updated: " + uploadTraffic);
          }
        }
      }
    } catch (Exception e) {
      appendText("패킷 분석 오류 발생 : " + e.getMessage());
    }
    // appendText("processPacket 메서드 작업 완료");
    return new int[]{uploadTraffic, downloadTraffic};
  }

  private void updateTrafficValues(int uploadTraffic, int downloadTraffic) {
    // 증가 시에는 실시간 트래픽 값을 사용하고, 감소 시에는 EMA를 적용
    if (uploadTraffic > smoothedUploadTraffic) {
      smoothedUploadTraffic = uploadTraffic; // 증가 시 실시간 값 사용
    } else {
      smoothedUploadTraffic = alpha * uploadTraffic + (1 - alpha) * smoothedUploadTraffic; // 감소 시 EMA 적용
    }

    if (downloadTraffic > smoothedDownloadTraffic) {
      smoothedDownloadTraffic = downloadTraffic; // 증가 시 실시간 값 사용
    } else {
      smoothedDownloadTraffic = alpha * downloadTraffic + (1 - alpha) * smoothedDownloadTraffic; // 감소 시 EMA 적용
    }

    // UI에 업데이트된 트래픽 값 표시
    SwingUtilities.invokeLater(() -> {
      uploadLabel.setText((int) smoothedUploadTraffic + " 바이트");
      downloadLabel.setText((int) smoothedDownloadTraffic + " 바이트");
    });

    // 알림 조건을 위한 트래픽 값 계산
    int alertUploadTraffic = alertOnUpload ? (int) smoothedUploadTraffic : 0;
    int alertDownloadTraffic = alertOnDownload ? (int) smoothedDownloadTraffic : 0;

    // 임계값 조건 확인 및 알림 처리 (alertUploadTraffic, alertDownloadTraffic 추가)
    evaluateTrafficConditions((int) smoothedUploadTraffic, (int) smoothedDownloadTraffic, alertUploadTraffic, alertDownloadTraffic);
  }


  private void evaluateTrafficConditions(int uploadTraffic, int downloadTraffic, int alertUploadTraffic, int alertDownloadTraffic) {
    long currentTime = System.currentTimeMillis();

    // 1. 팝업 상태를 판단하는 로직
    int totalAlertTraffic = alertUploadTraffic + alertDownloadTraffic;

    // 팝업 닫기 조건: 3초 동안 임계값 이하로 유지되는 경우
    if (totalAlertTraffic <= threshold) {
      if (currentTime > lastPopupCloseTime + 3000) { // 3초가 경과했는지 확인
        closePopup();  // 팝업을 닫기
      }
    } else {
      // 트래픽이 임계값을 초과하면 팝업을 유지하고, 마지막 팝업 닫힌 시간 갱신
      showPopup();  // 팝업을 열기
      lastPopupCloseTime = currentTime;
    }

    // 2. "미접속" 상태로 표현할지 판단하는 로직
    boolean isUploadDisconnected = false;
    boolean isDownloadDisconnected = false;

    // 업로드 미접속 상태 판단
    if (uploadTraffic < 50 && currentTime > lastUploadDisconnectedTime + 3000) {
      isUploadDisconnected = true;
      lastUploadDisconnectedTime = currentTime;
    } else if (uploadTraffic >= 50) {
      lastUploadDisconnectedTime = currentTime; // 업로드 트래픽이 임계점을 넘으면 시간 초기화
    }

    // 다운로드 미접속 상태 판단
    if (downloadTraffic < 50 && currentTime > lastDownloadDisconnectedTime + 3000) {
      isDownloadDisconnected = true;
      lastDownloadDisconnectedTime = currentTime;
    } else if (downloadTraffic >= 50) {
      lastDownloadDisconnectedTime = currentTime; // 다운로드 트래픽이 임계점을 넘으면 시간 초기화
    }
    // 미접속 상태 UI 업데이트 호출
    updateDisconnectedUI(isUploadDisconnected, isDownloadDisconnected);
  }

  private void showPopup() {
    if (!isRunning) {
      return; // 정지 상태일 경우 메서드를 종료
    }
    // appendText("showPopup 호출됨");
    // 팝업이 출력중이지 않을때 팝업 표시
    if (!popup.isVisible()) {
      SwingUtilities.invokeLater(() -> {
        // appendText("팝업 출력");
        popup.showPopup();
      });
    }
  }

  private void closePopup() {
    if (popup.isVisible()) {
      SwingUtilities.invokeLater(() -> {
        popup.hidePopup();  // 팝업을 닫기
      });
    }
  }

  private void updateDisconnectedUI(boolean isUploadDisconnected, boolean isDownloadDisconnected) {
    SwingUtilities.invokeLater(() -> {
      if (isUploadDisconnected) {
        uploadLabel.setText("미접속");
      }
      if (isDownloadDisconnected) {
        downloadLabel.setText("미접속");
      }
    });
  }

  public void stopMonitoring() {
    // appendText("stopMonitoring 호출됨");

    SwingWorker<Void, Void> stopWorker = new SwingWorker<>() {
      @Override
      protected Void doInBackground() {
        try {
          isRunning = false;

          if (scheduledTask != null && !scheduledTask.isCancelled()) {
            scheduledTask.cancel(true);  // 예약된 작업 취소
            appendText("스케줄된 작업 취소됨");
          }
          if (scheduler != null && !scheduler.isShutdown()) {
              scheduler.shutdown(); // 진행중인 작업의 완료를 기다리고 종료
              appendText("스케줄러 종료 시도");
            try{
              if(!scheduler.awaitTermination(3, TimeUnit.SECONDS)){ // 3초이내에 종료 안되면
                scheduler.shutdownNow(); // 강종
                appendText("스케줄러 강제 종료");
              }
            } catch (InterruptedException e) {
              scheduler.shutdownNow(); // 예외 발생시에도 강제종료
            }
            appendText("스케줄러 종료 완료");
          }

          // ExecutorService 종료 로직 추가
          if (executorService != null && !executorService.isShutdown()) {
              executorService.shutdown();
              appendText("패킷 캡처 스레드 종료 시도");
              try{
                if(!executorService.awaitTermination(3, TimeUnit.SECONDS)){
                  executorService.shutdownNow();
                  appendText("패킷 캡처 스레드 강제 종료");
                }
              } catch (InterruptedException e) {
                executorService.shutdownNow();
              }
            appendText("패킷 캡처 스레드 종료 완료");
          }

          appendText("모니터링 종료");

        } catch (Exception e) {
          // appendText("모니터링 종료 중 오류 발생: " + e.getMessage());
          e.printStackTrace();
        }
        return null;
      }
    };
    stopWorker.execute();
  }

  public static void appendText(String newText) {
    if (!textBox.getText().isEmpty()) {
      textBox.append("\n");
    }
    textBox.append(newText);
    textBox.setCaretPosition(textBox.getDocument().getLength());
  }

  public void shutdown() {
    stopMonitoring();
    if (scheduler != null && !scheduler.isShutdown()) {
      scheduler.shutdownNow();
    }
  }
}
