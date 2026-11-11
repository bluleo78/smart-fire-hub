package gateprobe.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;

/**
 * {@code MyBatisPlatformConfigGateTest} 전용 시험 구현. 운영 스캔 범위({@code com.smartfirehub}) 밖의 {@code
 * gateprobe} 패키지에 두어, 다른 통합 테스트 컨텍스트의 등록기 스캔에 걸리지 않게 한다.
 */
@PlatformMyBatisRepository
public class GateProbeRepository {}
