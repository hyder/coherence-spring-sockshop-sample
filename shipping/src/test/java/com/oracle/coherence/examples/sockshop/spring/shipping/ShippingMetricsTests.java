/*
 * Copyright (c) 2021, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.oracle.coherence.examples.sockshop.spring.shipping;

import java.time.LocalDate;
import java.util.List;

import com.oracle.coherence.examples.sockshop.spring.test.config.TestSpanConfig;
import com.oracle.coherence.examples.sockshop.spring.test.tracing.CustomSpanFilter;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import io.micrometer.tracing.exporter.FinishedSpan;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.StringUtils;

import static com.oracle.coherence.examples.sockshop.spring.shipping.TestDataFactory.shippingRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration test to ensure Coherence metrics are properly exposed when
 * property {@code coherence.metrics.http.enabled} is set to {@code true}.
 *
 * @author Gunnar Hillert
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"management.tracing.sampling.probability=1.0",
				"coherence.metrics.http.enabled=true"
		}
)
@AutoConfigureObservability
@AutoConfigureWebTestClient
@DirtiesContext
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Import(TestSpanConfig.class)
@Slf4j
public class ShippingMetricsTests {

	public static final int COHERENCE_METRICS_PORT = 9612;
	public static final String COHERENCE_METRICS_URL = "http://localhost:" + COHERENCE_METRICS_PORT + "/metrics";
	public static final String SHIPPING_URL = "/shipping";

	@Autowired
	protected WebTestClient webTestClient;

	@Autowired
	private CustomSpanFilter spanHandler;

	@Test
	@Order(1)
	void addShippingRequest() {
		webTestClient.post()
				.uri(SHIPPING_URL)
				.bodyValue(shippingRequest("A123", 1))
				.exchange()
				.expectStatus().isOk()
				.expectBody()
					.jsonPath("carrier").isEqualTo("FEDEX")
					.jsonPath("deliveryDate").isEqualTo(LocalDate.now().plusDays(1).toString());
	}

	@Test
	@Order(2)
	void verifyCoherenceMetrics() {
		this.webTestClient.get()
				.uri(COHERENCE_METRICS_URL + "/Coherence.Cache.Size?name=shipments&tier=back")
				.accept(MediaType.APPLICATION_JSON)
				.exchange()
				.expectStatus().isOk()
				.expectBody()
					.consumeWith(System.out::println)
					.jsonPath("$.length()").isEqualTo(1)
					.jsonPath("$[0].tags.name").isEqualTo("shipments")
					.jsonPath("$[0].value").isEqualTo(1);
	}

	@Test
	@Order(3)
	void verifyMicrometerTraces() {
		await().untilAsserted(() ->
				assertThat(this.spanHandler.getSpans())
						.hasSize(1));

		final List<FinishedSpan> spans = this.spanHandler.getSpans();
		log.info("\n" + StringUtils.collectionToDelimitedString(spans, "\n"));

		assertThat(spans)
				.extracting(finishedSpan -> finishedSpan.getTags().get("method"))
				.containsExactlyInAnyOrder("POST");
		assertThat(spans)
				.extracting(finishedSpan -> finishedSpan.getTags().get("uri"))
				.containsExactlyInAnyOrder("/shipping");
	}
}
