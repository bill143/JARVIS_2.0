package com.jarvis.integrations.llm;

import java.io.IOException;
import java.util.List;

/**
 * Provider-agnostic seam over a chat-completion model. {@link AnthropicPolicy} talks to the model
 * exclusively through this interface, so adding OpenAI, Gemini, or Groq later is a new
 * implementation class rather than surgery on the policy.
 *
 * <p>The request/response shapes are deliberately neutral: a {@link Request} is a model name, a
 * system prompt, an ordered list of {@link Message}s, and a token cap; a {@link Result} is the
 * assistant's text plus the token counts the call consumed. Token usage is surfaced here (even
 * though nothing consumes it yet) so provider-agnostic usage metering can be wired in later without
 * touching every call site.
 */
public interface LlmProvider {

    /** Sends {@code request} to the model and returns its text answer plus token usage. */
    Result complete(Request request) throws IOException, InterruptedException;

    /** One conversation message. {@code role} is "user" or "assistant". */
    record Message(String role, String content) {
    }

    /** A single completion request, independent of any provider's wire format. */
    record Request(String model, String system, List<Message> messages, int maxTokens) {
    }

    /**
     * A completion result. {@code inputTokens}/{@code outputTokens} are the usage the call
     * reported, or {@code 0} when the provider did not include usage in its response.
     */
    record Result(String text, long inputTokens, long outputTokens) {
    }
}
