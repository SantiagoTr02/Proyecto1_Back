package org.breaze.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Person {
    private String fullName;
    private String documentId;
    private int age;
    private char sex; // 'M'/'F'/'O'
    private String contactEmail;
    private String clinicalNotes;
}
