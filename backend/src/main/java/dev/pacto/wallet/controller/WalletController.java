package dev.pacto.wallet.controller;

import dev.pacto.ledger.InvalidPaginationException;
import dev.pacto.wallet.dto.WalletBalanceResponse;
import dev.pacto.wallet.dto.WalletStatementResponse;
import dev.pacto.wallet.service.UserNotFoundException;
import dev.pacto.wallet.service.WalletQueryService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/users/{userId}/wallet")
public class WalletController {

    private final WalletQueryService walletQueryService;

    public WalletController(
            WalletQueryService walletQueryService) {
        this.walletQueryService = walletQueryService;
    }

    @GetMapping("/balance")
    public WalletBalanceResponse getBalance(
            @PathVariable("userId") UUID userId) {
        return walletQueryService.getBalance(userId);
    }

    @GetMapping("/statement")
    public WalletStatementResponse getStatement(
            @PathVariable("userId") UUID userId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return walletQueryService.getStatement(
                userId,
                page,
                size);
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ProblemDetail handleUserNotFound(
            UserNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                exception.getMessage());
    }

    @ExceptionHandler(InvalidPaginationException.class)
    public ProblemDetail handleInvalidPagination(
            InvalidPaginationException exception) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                exception.getMessage());
    }
}