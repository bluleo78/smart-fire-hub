package com.smartfirehub.proactive.service.delivery;

import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.dto.ProactiveResult;

public interface DeliveryChannel {

  String type();

  void deliver(ProactiveJobResponse job, Long executionId, ProactiveResult result);
}
