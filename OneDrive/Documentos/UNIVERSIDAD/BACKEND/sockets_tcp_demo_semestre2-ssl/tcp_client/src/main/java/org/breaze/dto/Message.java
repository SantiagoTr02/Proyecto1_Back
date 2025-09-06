package org.breaze.dto;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import org.breaze.enums.ActionEnum;

@AllArgsConstructor
@NoArgsConstructor
public class Message {
    private ActionEnum actionEnum;
    private String payload;

    public ActionEnum getActionEnum() {
        return actionEnum;
    }

    public void setActionEnum(ActionEnum actionEnum) {
        this.actionEnum = actionEnum;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    @Override
    public String toString() {
        return payload;
    }
}
