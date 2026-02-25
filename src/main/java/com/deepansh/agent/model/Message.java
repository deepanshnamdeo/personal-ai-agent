package com.deepansh.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Message {

    public enum Role {
        system, user, assistant, tool
    }

    private Role role;
    private String content;

    /** Present when role = tool: links back to the assistant's tool_call id */
    private String toolCallId;

    /** Present when role = tool: the name of the tool that produced this result */
    private String name;
}
