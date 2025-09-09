package org.breaze.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.breaze.enums.ActionEnum;
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Message {
    private ActionEnum actionEnum;
    private String payload;





}
