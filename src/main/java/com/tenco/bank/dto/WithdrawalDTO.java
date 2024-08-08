package com.tenco.bank.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@ToString
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
public class WithdrawalDTO {
	private Long amount;
	private String wAccountNumber;
	private String wAccountPassword;
	
	
}
