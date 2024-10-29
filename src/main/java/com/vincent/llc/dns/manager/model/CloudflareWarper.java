package com.vincent.llc.dns.manager.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CloudflareWarper {
    private CloudflareResult result;
    private boolean success;
    private List<Object> errors;
    private List<Object> messages;
}