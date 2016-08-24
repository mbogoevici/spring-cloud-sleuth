package org.springframework.cloud.sleuth.zipkin.stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.cloud.sleuth.Log;
import org.springframework.cloud.sleuth.Span;
import org.springframework.cloud.sleuth.stream.SleuthSink;
import org.springframework.cloud.sleuth.stream.Spans;
import org.springframework.cloud.sleuth.zipkin.stream.ZipkinMessageListener.NotSleuthStreamClient;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.integration.annotation.MessageEndpoint;
import zipkin.Annotation;
import zipkin.BinaryAnnotation;
import zipkin.BinaryAnnotation.Type;
import zipkin.Endpoint;
import zipkin.Span.Builder;
import zipkin.collector.Collector;
import zipkin.collector.CollectorMetrics;
import zipkin.collector.CollectorSampler;
import zipkin.storage.Callback;
import zipkin.storage.StorageComponent;

import java.io.UnsupportedEncodingException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A message listener that is turned on if Sleuth Stream is disabled.
 * Asynchronously stores the received spans using {@link Collector}.
 *
 * @author Dave Syer
 * @since 1.0.0
 *
 * @see NotSleuthStreamClient
 */
@MessageEndpoint
@Conditional(NotSleuthStreamClient.class)
public class ZipkinMessageListener {

	private static final org.apache.commons.logging.Log log = org.apache.commons.logging.LogFactory
			.getLog(ZipkinMessageListener.class);
	static final String UNKNOWN_PROCESS_ID = "unknown";
	final Collector collector;

	/** lazy so transient storage errors don't crash bootstrap */
	@Lazy
	@Autowired
	ZipkinMessageListener(StorageComponent storage, CollectorSampler sampler,
			CollectorMetrics metrics) {
		this.collector = Collector.builder(getClass())
				.storage(storage)
				.sampler(sampler)
				.metrics(metrics.forTransport("stream")).build();
	}

	@StreamListener(SleuthSink.INPUT)
	public void sink(Spans input) {
		List<zipkin.Span> converted = ConvertToZipkinSpanList.convert(input);
		this.collector.accept(converted, Callback.NOOP);
	}

	/**
	 * Add annotations from the sleuth Span.
	 */
	static void addZipkinAnnotations(Builder zipkinSpan, Span span, Endpoint endpoint) {
		for (Log ta : span.logs()) {
			Annotation zipkinAnnotation = Annotation.builder()
					.endpoint(endpoint)
					.timestamp(ta.getTimestamp() * 1000) // Zipkin is in microseconds
					.value(ta.getEvent())
					.build();
			zipkinSpan.addAnnotation(zipkinAnnotation);
		}
	}

	/**
	 * Adds binary annotations from the sleuth Span
	 */
	static void addZipkinBinaryAnnotations(Builder zipkinSpan, Span span,
			Endpoint endpoint) {
		for (Map.Entry<String, String> e : span.tags().entrySet()) {
			BinaryAnnotation.Builder binaryAnn = BinaryAnnotation.builder();
			binaryAnn.type(Type.STRING);
			binaryAnn.key(e.getKey());
			try {
				binaryAnn.value(e.getValue().getBytes("UTF-8"));
			}
			catch (UnsupportedEncodingException ex) {
				log.error("Error encoding string as UTF-8", ex);
			}
			binaryAnn.endpoint(endpoint);
			zipkinSpan.addBinaryAnnotation(binaryAnn.build());
		}
	}

	protected static class NotSleuthStreamClient extends SpringBootCondition {

		@Override
		public ConditionOutcome getMatchOutcome(ConditionContext context,
				AnnotatedTypeMetadata metadata) {
			Environment environment = context.getEnvironment();
			if ("true".equals(environment
					.resolvePlaceholders("${spring.sleuth.stream.enabled:}"))) {
				return ConditionOutcome
						.noMatch("Found spring.sleuth.stream.enabled=true");
			}
			if (environment instanceof ConfigurableEnvironment) {
				ConfigurableEnvironment configurable = (ConfigurableEnvironment) environment;
				configurable.getPropertySources()
						.addLast(
								new MapPropertySource("spring.sleuth.stream",
										Collections.<String, Object>singletonMap(
												"spring.sleuth.stream.enabled",
												"false")));
			}
			return ConditionOutcome.match("Not found: spring.sleuth.stream.enabled");
		}

	}
}
