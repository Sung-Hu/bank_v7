package com.tenco.bank.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tenco.bank.dto.DepositDTO;
import com.tenco.bank.dto.SaveDTO;
import com.tenco.bank.dto.TransferDTO;
import com.tenco.bank.dto.WithdrawalDTO;
import com.tenco.bank.handler.exception.DataDeliveryException;
import com.tenco.bank.handler.exception.RedirectException;
import com.tenco.bank.repository.interfaces.AccountRepository;
import com.tenco.bank.repository.interfaces.HistoryRepository;
import com.tenco.bank.repository.model.Account;
import com.tenco.bank.repository.model.History;
import com.tenco.bank.utils.Define;
@Service
public class AccountService {
	@Autowired
	private final AccountRepository accountRepository;
	private final HistoryRepository historyRepository;
	
	public AccountService(AccountRepository accountRepository, HistoryRepository historyRepository) {
		this.accountRepository = accountRepository;
		this.historyRepository = historyRepository;
	}
	@Transactional
	public void createAccount(SaveDTO dto, Integer principalId) {
		int result = 0; 
		try {
			result = accountRepository.insert(dto.toAccount(principalId));
		} catch (DataAccessException e) {
			throw new DataDeliveryException("중복된 이름을 사용할 수 없습니다", HttpStatus.INTERNAL_SERVER_ERROR);
		} catch (Exception e) {
			throw new RedirectException("알 수 없는 오류", HttpStatus.SERVICE_UNAVAILABLE);
		}
		if(result == 0) {
			throw new DataDeliveryException("실패 두둥", HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}
	
	public List<Account> readAccountListByUserId(Integer userId) {
		List<Account> accountListEntity = null;
		try {
			accountListEntity = accountRepository.findByUserId(userId);
		} catch (DataAccessException e) {
			throw new DataDeliveryException("잘못된 처리 입니다", HttpStatus.INTERNAL_SERVER_ERROR);
		} catch (Exception e) {
			throw new RedirectException("알 수 없는 오류", HttpStatus.SERVICE_UNAVAILABLE);
		}
		return accountListEntity ;
	}
	
	// 1. 사용자가 던진 계좌번호의 존재 여부 확인 -- select
	// 2. 본인 계좌가 맞는지 여부 확인 -- 객체 상태값에서 비교
	// 3. 계좌 비밀번호 확인 -- 객체 상태값에서 일치 여부 확인
	// 4. 잔액 여부 확인 -- 객체 상태값에서 확인
	// 5. 출금 처리 -- update
	// 6. 거래 내역 등록 -- insert(history)
	// 7. 트랜잭션 처리 -- 오류가 났을시 롤백 해야함!
	@Transactional
	public void updateAccountWithdraw(WithdrawalDTO dto, Integer principalId) {
		// 1 .
		Account accountEntity = accountRepository.findByNumber(dto.getWAccountNumber());
		if(accountEntity == null) {
			throw new DataDeliveryException(Define.NOT_EXIST_ACCOUNT, HttpStatus.BAD_REQUEST);
		}
		// 2.
		accountEntity.checkOwner(principalId);
		// 3.
		accountEntity.checkPassword(dto.getWAccountPassword());
		// 4.
		accountEntity.checkBalance(dto.getAmount());
		// 5.
		// accountEntity 객체의 잔액을 변경하고 없데이트 처리해야 한다.
		accountEntity.withdraw(dto.getAmount());
		accountRepository.updateById(accountEntity);
		// 6.
//		insert into history_tb(amount, w_balance, d_balance, w_account_id, d_account_id)
//        values(#{amount}, #{wBalance}, #{dBalance}, #{wAccountId}, #{dAccountId} )
		History history = new History();
		history.setAmount(dto.getAmount());
		history.setWBalance(accountEntity.getBalance());
		history.setDBalance(null);
		history.setWAccountId(accountEntity.getId());
		history.setDAccountId(null);
		
		int rowResultCount = historyRepository.insert(history);
		if(rowResultCount != 1) {
			throw new DataDeliveryException(Define.FAILED_PROCESSING, HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}
	   // 1. 계좌 존재 여부를 확인
    // 2. 본인 계좌 여부를 확인 -- 객체 상태값에서 비교
    // 3. 입금 처리 -- update
    // 4. 거래 내역 등록 -- insert(history)
    @Transactional
    public void updateAccountDeposit(DepositDTO dto, Integer principalId) {
        // 1.
        Account accountEntity = accountRepository.findByNumber(dto.getDAccountNumber());
        if (accountEntity == null) {
            throw new DataDeliveryException(Define.NOT_EXIST_ACCOUNT, HttpStatus.BAD_REQUEST);
        }
        // 2.
        accountEntity.checkOwner(principalId);
        // 3.
        accountEntity.deposit(dto.getAmount());
        accountRepository.updateById(accountEntity);
        // 4.
        History history = History.builder()
            .amount(dto.getAmount())
            .dAccountId(accountEntity.getId())
            .dBalance(accountEntity.getBalance())
            .wAccountId(null)
            .wBalance(null)
            .build();
        int rowResultCount = historyRepository.insert(history);
        if (rowResultCount != 1) {
            throw new DataDeliveryException(Define.FAILED_PROCESSING, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
    
    // 이체 기능 만들기
    // 1. 출금 계좌 존재 여부 확인 - select
    // 2. 입금 계좌 존재 여부 확인 - select
    // 3. 출금 계좌 본인 소유 확인 - 객체 상태값과 세션아이디 비교
    // 4. 출금 계좌 비밀 번호 확인 -  객체 상태값과 dto 비밀번호 비교
    // 5. 출금 계좌 잔액 확인 - 객체 상태값과 dto 비교
    // 6. 입금 계좌 객체 상태값 변경 처리 (거래 금액 증가)
    // 7. 입금 계좌 업데이트처리
    // 8. 출금 계좌 객체 상태값 변경 처리 (잔액 - 거래금액)
    // 9. 출금 계좌 업데이트처리
    // 10. 거래 내역 등록 처리
    // 11. 트랜잭션 처리
    @Transactional
    public void updateAccountTransfer(TransferDTO dto, Integer principalId) {
    	
    	// 출금 계좌 정보 조회 
    	Account withdrawAccountEntity = accountRepository.findByNumber(dto.getWAccountNumber());
    	// 입금 계좌 정보 조회 
    	Account depositAccountEntity = accountRepository.findByNumber(dto.getDAccountNumber());

    	if (withdrawAccountEntity == null) {
    		throw new DataDeliveryException(Define.NOT_EXIST_ACCOUNT, HttpStatus.INTERNAL_SERVER_ERROR);
    	}

    	if (depositAccountEntity == null) {
    		throw new DataDeliveryException("상대방의 계좌 번호가 없습니다.", HttpStatus.INTERNAL_SERVER_ERROR);
    	}

    	withdrawAccountEntity.checkOwner(principalId);
    	withdrawAccountEntity.checkPassword(dto.getPassword());
    	withdrawAccountEntity.checkBalance(dto.getAmount());
    	withdrawAccountEntity.withdraw(dto.getAmount());
    	depositAccountEntity.deposit(dto.getAmount());

    	int resultRowCountWithdraw = accountRepository.updateById(withdrawAccountEntity);
    	int resultRowCountDeposit = accountRepository.updateById(depositAccountEntity);
    	
    	if(resultRowCountWithdraw != 1 && resultRowCountDeposit != 1) {
    		throw new DataDeliveryException(Define.FAILED_PROCESSING, HttpStatus.INTERNAL_SERVER_ERROR);
    	}
    	
    	History history = History.builder().amount(dto.getAmount()) // 이체 금액
    			.wAccountId(withdrawAccountEntity.getId()) // 출금 계좌
    			.dAccountId(depositAccountEntity.getId()) // 입금 계좌
    			.wBalance(withdrawAccountEntity.getBalance()) // 출금 계좌 남은 잔액
    			.dBalance(depositAccountEntity.getBalance()) // 입금 계좌 남은 잔액
    			.build();
    	
    	int resultRowCountHistory =  historyRepository.insert(history);
    	if(resultRowCountHistory != 1) {
    		throw new DataDeliveryException(Define.FAILED_PROCESSING, HttpStatus.INTERNAL_SERVER_ERROR);
    	}
    }
         
}
