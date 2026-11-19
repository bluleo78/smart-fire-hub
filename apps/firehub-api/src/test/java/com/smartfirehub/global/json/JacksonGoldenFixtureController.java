package com.smartfirehub.global.json;

import com.smartfirehub.audit.dto.AuditLogResponse;
import com.smartfirehub.dataset.dto.CategoryResponse;
import com.smartfirehub.dataset.dto.DatasetResponse;
import com.smartfirehub.dataset.dto.RowDataResponse;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.pipeline.dto.PipelineExecutionResponse;
import com.smartfirehub.platform.dto.PlatformAccountResponse;
import com.smartfirehub.platform.dto.TenantSummaryResponse;
import com.smartfirehub.role.exception.RoleNotFoundException;
import com.smartfirehub.securitylevel.dto.SecurityLevelSummary;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Jackson 골든 특성 시험(WD-793) 전용 픽스처 컨트롤러.
 *
 * <p>GET 은 고정 값으로 만든 실제 응답 DTO 와 직렬화 규칙 표본을 돌려주고, POST 는 받은 본문 객체를 그대로 다시 써 준다. 이렇게 해서 실제 HTTP 메시지
 * 변환기(요청 읽기·응답 쓰기) 경로를 그대로 타면서 Jackson 2→3 전환 전후의 JSON 바이트를 비교한다.
 *
 * <p>스프링 6 부터 핸들러 매핑은 {@code @Controller} 만 컨트롤러로 보므로 {@code @Controller} 를 붙이되, 컴포넌트 스캔을 하는 다른 통합
 * 시험 (@SpringBootTest)에 이 시험용 경로가 섞이지 않도록 {@code @Profile("jackson-golden")} 로 가둔다. 골든 시험만 그 프로필을
 * 켠다. 경로는 인터셉터 대상({@code /api/v1/**})을 피해 {@code /golden} 아래에 둔다.
 */
@Controller
@Profile(JacksonGoldenFixtureController.PROFILE)
@RequestMapping("/golden")
public class JacksonGoldenFixtureController {

  /** 이 컨트롤러를 켜는 스프링 프로필 이름. */
  public static final String PROFILE = "jackson-golden";

  // ---------------------------------------------------------------- 응답 DTO 표본

  /** StorageZoneDateTimeSerializer(@JsonSerialize)·@JsonRawValue 가 걸린 감사 로그 응답. */
  @GetMapping("/audit-log")
  @ResponseBody
  public AuditLogResponse auditLog() {
    return new AuditLogResponse(
        1L,
        7L,
        "admin",
        "CREATE",
        "dataset",
        "5",
        "데이터셋 생성 \"따옴표\"와 <태그> & 한글",
        LocalDateTime.of(2026, 2, 15, 10, 0, 0),
        "127.0.0.1",
        null,
        "SUCCESS",
        null,
        "{\"a\":1,\"b\":[true,null]}");
  }

  /**
   * DatasetRowValuesSerializer 표본 — BigDecimal·LocalDate·LocalDateTime·null·중첩 Map·안전 범위 밖 Long·배열.
   */
  @GetMapping("/row-data")
  @ResponseBody
  public RowDataResponse rowData() {
    Map<String, Object> nested = new LinkedHashMap<>();
    nested.put("z", 1);
    nested.put("a", new BigDecimal("2.50"));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("name", "홍길동");
    data.put("amount", new BigDecimal("100.10"));
    data.put("tiny", new BigDecimal("1E-7"));
    data.put("day", LocalDate.of(2026, 1, 15));
    data.put("at", LocalDateTime.of(2026, 1, 15, 10, 0, 0));
    data.put("nothing", null);
    data.put("nested", nested);
    data.put("small", 42);
    data.put("safeLong", 9007199254740991L);
    data.put("bigLong", 9007199254740993L);
    data.put("bigInt", new BigInteger("123456789012345678901234567890"));
    data.put("dbl", 1.5d);
    data.put("flag", true);
    data.put("list", List.of(new BigDecimal("0.10"), 3, "x"));
    return new RowDataResponse(10L, data, LocalDateTime.of(2026, 1, 15, 10, 0, 0));
  }

  /** 플랫폼 계정 응답(StorageZoneDateTimeSerializer). */
  @GetMapping("/platform-account")
  @ResponseBody
  public PlatformAccountResponse platformAccount() {
    return new PlatformAccountResponse(
        3L, "op", "op@example.com", "운영자", true, false, 2, LocalDateTime.of(2026, 3, 1, 9, 30, 15));
  }

  /** 테넌트 요약 응답(StorageZoneDateTimeSerializer). */
  @GetMapping("/tenant-summary")
  @ResponseBody
  public TenantSummaryResponse tenantSummary() {
    return new TenantSummaryResponse(
        4L, "acme", "에이콤", "ACTIVE", 12, LocalDateTime.of(2026, 3, 1, 9, 30, 15));
  }

  /** 데이터셋 목록 응답 — 실제 컨트롤러 반환 형태(PageResponse&lt;DatasetResponse&gt;). 중첩 DTO·null·빈 List 포함. */
  @GetMapping("/dataset-list")
  @ResponseBody
  public PageResponse<DatasetResponse> datasetList() {
    DatasetResponse full =
        new DatasetResponse(
            1L,
            "화재 이력",
            "fire_history",
            "설명",
            new CategoryResponse(2L, "재난", null),
            "INTERNAL",
            "MANUAL",
            LocalDateTime.of(2026, 1, 15, 10, 0, 0),
            true,
            List.of("a", "b"),
            "ACTIVE",
            null,
            null,
            null,
            null,
            new SecurityLevelSummary(1L, "내부", 2, false, "ALLOW", "ALLOW", "ALLOW", true),
            null,
            true);
    DatasetResponse sparse =
        new DatasetResponse(
            2L,
            "비어 있는",
            "empty_one",
            null,
            null,
            "INTERNAL",
            "MANUAL",
            LocalDateTime.of(2026, 1, 16, 0, 0, 0),
            false,
            List.of(),
            "DEPRECATED",
            "사유",
            "admin",
            LocalDateTime.of(2026, 1, 17, 12, 0, 0),
            9L);
    return new PageResponse<>(List.of(full, sparse), 0, 20, 2, 1);
  }

  /** 파이프라인 실행 이력 응답 — 실제 컨트롤러 반환 형태(PipelineExecutionResponse). */
  @GetMapping("/pipeline-execution")
  @ResponseBody
  public List<PipelineExecutionResponse> pipelineExecution() {
    return List.of(
        new PipelineExecutionResponse(
            100L,
            5L,
            "COMPLETED",
            "admin",
            LocalDateTime.of(2026, 1, 15, 10, 0, 0),
            LocalDateTime.of(2026, 1, 15, 10, 5, 30),
            LocalDateTime.of(2026, 1, 15, 9, 59, 59),
            "SCHEDULE",
            "매일 아침",
            null),
        new PipelineExecutionResponse(
            101L, 5L, "RUNNING", null, LocalDateTime.of(2026, 1, 16, 1, 0, 0), null, null));
  }

  // ---------------------------------------------------------------- 직렬화 규칙 표본

  /** 날짜·시각 묶음 — LocalDate·LocalDateTime·OffsetDateTime·Instant·Duration. */
  @GetMapping("/dates")
  @ResponseBody
  public DateBundle dates() {
    return new DateBundle(
        LocalDate.of(2026, 1, 15),
        LocalDateTime.of(2026, 1, 15, 10, 0, 0),
        OffsetDateTime.of(2026, 1, 15, 10, 0, 0, 0, ZoneOffset.ofHours(9)),
        Instant.parse("2026-01-15T10:00:00.123Z"),
        Duration.ofMinutes(90));
  }

  /** BigDecimal 표본 — 1E-7 이 과학 표기로 나가는지, 100.10 의 끝자리 0 이 남는지. */
  @GetMapping("/decimals")
  @ResponseBody
  public DecimalBundle decimals() {
    return new DecimalBundle(
        new BigDecimal("1E-7"),
        new BigDecimal("100.10"),
        new BigDecimal("12345678901234567890.123456789"),
        1.0d,
        1L << 60);
  }

  /** Optional 필드 — 값 있음/없음. */
  @GetMapping("/optional")
  @ResponseBody
  public OptionalBundle optional() {
    return new OptionalBundle(Optional.of("값"), Optional.empty(), Optional.of(3));
  }

  /** enum 필드 — 이름으로 나간다. */
  @GetMapping("/enum")
  @ResponseBody
  public EnumBundle enumValue() {
    return new EnumBundle(Color.DARK_RED, List.of(Color.GREEN, Color.DARK_RED));
  }

  /** 빈 List·빈 Map. */
  @GetMapping("/empty-list")
  @ResponseBody
  public EmptyBundle emptyList() {
    return new EmptyBundle(List.of(), Map.of(), new String[0]);
  }

  /** null 필드 포함 여부 — 기본 설정에서는 null 도 그대로 쓰인다. */
  @GetMapping("/nulls")
  @ResponseBody
  public NullBundle nulls() {
    return new NullBundle(null, null, "x", null);
  }

  /** LinkedHashMap — 키 삽입 순서(z, a, m)가 유지돼야 한다. */
  @GetMapping("/linked-map")
  @ResponseBody
  public Map<String, Object> linkedMap() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("z", 1);
    map.put("a", 2);
    map.put("m", 3);
    return map;
  }

  /** 속성 선언 순서가 알파벳이 아닌 record — 선언 순서대로 나가야 한다. */
  @GetMapping("/declaration-order")
  @ResponseBody
  public OrderBundle declarationOrder() {
    return new OrderBundle("z", "b", "y", "a");
  }

  /** 전역 예외 처리기를 타는 오류 — 존재하지 않는 리소스(404). */
  @GetMapping("/error-not-found")
  @ResponseBody
  public Object errorNotFound() {
    throw new RoleNotFoundException();
  }

  // ---------------------------------------------------------------- 요청 에코

  /** 기본 에코 — 문자열·int·boolean·List. 알 수 없는 속성 무시, primitive 에 null 등을 본다. */
  @PostMapping("/echo")
  @ResponseBody
  public EchoBody echo(@RequestBody EchoBody body) {
    return body;
  }

  /** 객체 필드 에코 — 빈 문자열을 객체로 받는 경우. */
  @PostMapping("/echo-nested")
  @ResponseBody
  public NestedBody echoNested(@RequestBody NestedBody body) {
    return body;
  }

  /** 배열 필드 에코 — 단일 값을 배열로 받는 경우. */
  @PostMapping("/echo-tags")
  @ResponseBody
  public TagsBody echoTags(@RequestBody TagsBody body) {
    return body;
  }

  /** 날짜 문자열 에코. */
  @PostMapping("/echo-dates")
  @ResponseBody
  public DateInput echoDates(@RequestBody DateInput body) {
    return body;
  }

  // ---------------------------------------------------------------- 시험용 타입

  /** 시험용 enum — 이름에 밑줄이 있어 변환 규칙 차이를 드러낸다. */
  public enum Color {
    GREEN,
    DARK_RED
  }

  public record DateBundle(
      LocalDate localDate,
      LocalDateTime localDateTime,
      OffsetDateTime offsetDateTime,
      Instant instant,
      Duration duration) {}

  public record DecimalBundle(
      BigDecimal tiny, BigDecimal trailingZero, BigDecimal huge, double dbl, long bigLong) {}

  public record OptionalBundle(
      Optional<String> present, Optional<String> empty, Optional<Integer> number) {}

  public record EnumBundle(Color color, List<Color> colors) {}

  public record EmptyBundle(List<String> list, Map<String, String> map, String[] array) {}

  public record NullBundle(String a, Integer b, String c, List<String> d) {}

  public record OrderBundle(String zeta, String beta, String yankee, String alpha) {}

  public record EchoBody(String name, int count, boolean flag) {}

  public record NestedBody(String name, Inner inner) {}

  public record Inner(String value) {}

  public record TagsBody(String name, List<String> tags) {}

  public record DateInput(
      LocalDate localDate,
      LocalDateTime localDateTime,
      OffsetDateTime offsetDateTime,
      Instant instant) {}
}
