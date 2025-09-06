package org.breaze.helpers;

import org.breaze.dto.Message;
import org.breaze.enums.ActionEnum;

import java.util.Map;
import java.util.StringJoiner;

public class ProtocolManager {

    /**
     * Construye un mensaje en el formato usado por el servidor:
     * HEADER1;HEADER2;campo1=valor1|campo2=valor2|...
     */
    public Message buildMessage(ActionEnum actionEnum, Map<String, String> payload) {
        if (actionEnum == null) {
            throw new IllegalArgumentException("ActionEnum requerido");
        }

        StringBuilder sb = new StringBuilder();

        switch (actionEnum) {
            case CREATE_PATIENT:
                sb.append("CREATE_PATIENT|");
                appendKeyValuePairs(sb, payload);
                break;

            case GET_PERSON:
                sb.append("GET;patient;");
                appendKeyValuePairs(sb, payload);
                break;

            case UPDATE_PERSON_METADATA:
                sb.append("UPDATE;patient;");
                appendKeyValuePairs(sb, payload);
                break;

            case UPDATE_PERSON_GENOME:
                sb.append("UPDATE_GENOME;patient;");
                appendKeyValuePairs(sb, payload);
                break;

            case DEACTIVATE_PERSON:
                sb.append("DEACTIVATE;patient;");
                appendKeyValuePairs(sb, payload);
                break;

            case EXIT:
                sb.append("EXIT;patient;");
                break;

            default:
                throw new UnsupportedOperationException("Acción no soportada: " + actionEnum);
        }

        return new Message(actionEnum, sb.toString());
    }

    private void appendKeyValuePairs(StringBuilder sb, Map<String, String> payload) {
        if (payload == null || payload.isEmpty()) return;

        StringJoiner sj = new StringJoiner("|");
        for (Map.Entry<String, String> e : payload.entrySet()) {
            String v = (e.getValue() == null) ? "" : e.getValue();
            v = v.replace("\n", "").replace("\r", "");
            sj.add(e.getKey() + "=" + v);
        }
        sb.append(sj);
    }
}
