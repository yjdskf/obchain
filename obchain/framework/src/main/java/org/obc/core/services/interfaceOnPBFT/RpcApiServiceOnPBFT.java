package org.obc.core.services.interfaceOnPBFT;

import io.grpc.Server;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.obc.common.Service;
import org.obc.common.crypto.ECKey;
import org.obc.common.parameter.CommonParameter;
import org.obc.common.utils.StringUtil;
import org.obc.common.utils.Utils;
import org.obc.core.config.args.Args;
import org.obc.core.services.RpcApiService;
import org.obc.core.services.filter.LiteFnQueryGrpcInterceptor;
import org.obc.core.services.ratelimiter.RateLimiterInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.obc.api.DatabaseGrpc.DatabaseImplBase;
import org.obc.api.GrpcAPI;
import org.obc.api.GrpcAPI.AddressPrKeyPairMessage;
import org.obc.api.GrpcAPI.AssetIssueList;
import org.obc.api.GrpcAPI.BlockExtention;
import org.obc.api.GrpcAPI.BlockReference;
import org.obc.api.GrpcAPI.BytesMessage;
import org.obc.api.GrpcAPI.DecryptNotesTRC20;
import org.obc.api.GrpcAPI.DelegatedResourceList;
import org.obc.api.GrpcAPI.DelegatedResourceMessage;
import org.obc.api.GrpcAPI.EmptyMessage;
import org.obc.api.GrpcAPI.ExchangeList;
import org.obc.api.GrpcAPI.IvkDecryptTRC20Parameters;
import org.obc.api.GrpcAPI.NfTRC20Parameters;
import org.obc.api.GrpcAPI.NoteParameters;
import org.obc.api.GrpcAPI.NullifierResult;
import org.obc.api.GrpcAPI.NumberMessage;
import org.obc.api.GrpcAPI.OvkDecryptTRC20Parameters;
import org.obc.api.GrpcAPI.PaginatedMessage;
import org.obc.api.GrpcAPI.SpendResult;
import org.obc.api.GrpcAPI.TransactionExtention;
import org.obc.api.GrpcAPI.WitnessList;
import org.obc.api.WalletSolidityGrpc.WalletSolidityImplBase;
import org.obc.protos.Protocol.Account;
import org.obc.protos.Protocol.Block;
import org.obc.protos.Protocol.DynamicProperties;
import org.obc.protos.Protocol.Exchange;
import org.obc.protos.Protocol.MarketOrder;
import org.obc.protos.Protocol.MarketOrderList;
import org.obc.protos.Protocol.MarketOrderPair;
import org.obc.protos.Protocol.MarketOrderPairList;
import org.obc.protos.Protocol.MarketPriceList;
import org.obc.protos.Protocol.Transaction;
import org.obc.protos.Protocol.TransactionInfo;
import org.obc.protos.contract.AssetIssueContractOuterClass.AssetIssueContract;
import org.obc.protos.contract.ShieldContract.IncrementalMerkleVoucherInfo;
import org.obc.protos.contract.ShieldContract.OutputPointInfo;
import org.obc.protos.contract.SmartContractOuterClass.TriggerSmartContract;

@Slf4j(topic = "API")
public class RpcApiServiceOnPBFT implements Service {

  private int port = Args.getInstance().getRpcOnPBFTPort();
  private Server apiServer;

  @Autowired
  private WalletOnPBFT walletOnPBFT;

  @Autowired
  private RpcApiService rpcApiService;

  @Autowired
  private RateLimiterInterceptor rateLimiterInterceptor;

  @Autowired
  private LiteFnQueryGrpcInterceptor liteFnQueryGrpcInterceptor;

  @Override
  public void init() {
  }

  @Override
  public void init(CommonParameter parameter) {

  }

  @Override
  public void start() {
    try {
      NettyServerBuilder serverBuilder = NettyServerBuilder.forPort(port)
          .addService(new DatabaseApi());

      CommonParameter args = CommonParameter.getInstance();

      if (args.getRpcThreadNum() > 0) {
        serverBuilder = serverBuilder
            .executor(Executors.newFixedThreadPool(args.getRpcThreadNum()));
      }

      serverBuilder = serverBuilder.addService(new WalletPBFTApi());

      // Set configs from config.conf or default value
      serverBuilder
          .maxConcurrentCallsPerConnection(args.getMaxConcurrentCallsPerConnection())
          .flowControlWindow(args.getFlowControlWindow())
          .maxConnectionIdle(args.getMaxConnectionIdleInMillis(), TimeUnit.MILLISECONDS)
          .maxConnectionAge(args.getMaxConnectionAgeInMillis(), TimeUnit.MILLISECONDS)
          .maxMessageSize(args.getMaxMessageSize())
          .maxHeaderListSize(args.getMaxHeaderListSize());

      // add a ratelimiter interceptor
      serverBuilder.intercept(rateLimiterInterceptor);

      // add lite fullnode query interceptor
      serverBuilder.intercept(liteFnQueryGrpcInterceptor);

      apiServer = serverBuilder.build();
      rateLimiterInterceptor.init(apiServer);

      apiServer.start();

    } catch (IOException e) {
      logger.debug(e.getMessage(), e);
    }

    logger.info("RpcApiServiceOnPBFT started, listening on " + port);

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      System.err.println("*** shutting down gRPC server on PBFT since JVM is shutting down");
      //server.this.stop();
      System.err.println("*** server on PBFT shut down");
    }));
  }

  @Override
  public void stop() {
    if (apiServer != null) {
      apiServer.shutdown();
    }
  }

  /**
   * DatabaseApi.
   */
  private class DatabaseApi extends DatabaseImplBase {

    @Override
    public void getBlockReference(EmptyMessage request,
        StreamObserver<BlockReference> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getDatabaseApi().getBlockReference(request, responseObserver)
      );
    }

    @Override
    public void getNowBlock(EmptyMessage request, StreamObserver<Block> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getDatabaseApi().getNowBlock(request, responseObserver));
    }

    @Override
    public void getBlockByNum(NumberMessage request, StreamObserver<Block> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getDatabaseApi().getBlockByNum(request, responseObserver)
      );
    }

    @Override
    public void getDynamicProperties(EmptyMessage request,
        StreamObserver<DynamicProperties> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getDatabaseApi().getDynamicProperties(request, responseObserver)
      );
    }
  }

  /**
   * WalletPBFTApi.
   */
  private class WalletPBFTApi extends WalletSolidityImplBase {

    @Override
    public void getAccount(Account request, StreamObserver<Account> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getWalletSolidityApi().getAccount(request, responseObserver)
      );
    }

    @Override
    public void getAccountById(Account request, StreamObserver<Account> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getWalletSolidityApi().getAccountById(request, responseObserver)
      );
    }

    @Override
    public void listWitnesses(EmptyMessage request, StreamObserver<WitnessList> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getWalletSolidityApi().listWitnesses(request, responseObserver)
      );
    }

    @Override
    public void getAssetIssueById(BytesMessage request,
        StreamObserver<AssetIssueContract> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getWalletSolidityApi().getAssetIssueById(request, responseObserver)
      );
    }

    @Override
    public void getAssetIssueByName(BytesMessage request,
        StreamObserver<AssetIssueContract> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getWalletSolidityApi().getAssetIssueByName(request, responseObserver)
      );
    }

    @Override
    public void getAssetIssueList(EmptyMessage request,
        StreamObserver<AssetIssueList> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getWalletSolidityApi().getAssetIssueList(request, responseObserver)
      );
    }

    @Override
    public void getAssetIssueListByName(BytesMessage request,
        StreamObserver<AssetIssueList> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getWalletSolidityApi()
              .getAssetIssueListByName(request, responseObserver)
      );
    }

    @Override
    public void getPaginatedAssetIssueList(PaginatedMessage request,
        StreamObserver<AssetIssueList> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getWalletSolidityApi()
              .getPaginatedAssetIssueList(request, responseObserver)
      );
    }

    @Override
    public void getExchangeById(BytesMessage request,
        StreamObserver<Exchange> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getWalletSolidityApi().getExchangeById(
              request, responseObserver
          )
      );
    }

    @Override
    public void getNowBlock(EmptyMessage request, StreamObserver<Block> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getWalletSolidityApi().getNowBlock(request, responseObserver)
      );
    }

    @Override
    public void getNowBlock2(EmptyMessage request,
        StreamObserver<BlockExtention> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getWalletSolidityApi().getNowBlock2(request, responseObserver)
      );

    }

    @Override
    public void getBlockByNum(NumberMessage request, StreamObserver<Block> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getWalletSolidityApi().getBlockByNum(request, responseObserver)
      );
    }

    @Override
    public void getBlockByNum2(NumberMessage request,
        StreamObserver<BlockExtention> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getWalletSolidityApi().getBlockByNum2(request, responseObserver)
      );
    }

    @Override
    public void getDelegatedResource(DelegatedResourceMessage request,
        StreamObserver<DelegatedResourceList> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getWalletSolidityApi().getDelegatedResource(request, responseObserver)
      );
    }

    @Override
    public void getDelegatedResourceAccountIndex(BytesMessage request,
        StreamObserver<org.obc.protos.Protocol.DelegatedResourceAccountIndex> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getWalletSolidityApi()
              .getDelegatedResourceAccountIndex(request, responseObserver)
      );
    }

    @Override
    public void getTransactionCountByBlockNum(NumberMessage request,
        StreamObserver<NumberMessage> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getWalletSolidityApi()
              .getTransactionCountByBlockNum(request, responseObserver)
      );
    }

    @Override
    public void getTransactionById(BytesMessage request,
        StreamObserver<Transaction> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getWalletSolidityApi().getTransactionById(request, responseObserver)
      );

    }

    @Override
    public void getTransactionInfoById(BytesMessage request,
        StreamObserver<TransactionInfo> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getWalletSolidityApi()
              .getTransactionInfoById(request, responseObserver)
      );

    }

    @Override
    public void listExchanges(EmptyMessage request,
        StreamObserver<ExchangeList> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getWalletSolidityApi().listExchanges(request, responseObserver)
      );
    }

    @Override
    public void triggerConstantContract(TriggerSmartContract request,
        StreamObserver<TransactionExtention> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getWalletSolidityApi()
              .triggerConstantContract(request, responseObserver)
      );
    }


    @Override
    public void generateAddress(EmptyMessage request,
        StreamObserver<AddressPrKeyPairMessage> responseObserver) {
      ECKey ecKey = new ECKey(Utils.getRandom());
      byte[] priKey = ecKey.getPrivKeyBytes();
      byte[] address = ecKey.getAddress();
      String addressStr = StringUtil.encode58Check(address);
      String priKeyStr = Hex.encodeHexString(priKey);
      AddressPrKeyPairMessage.Builder builder = AddressPrKeyPairMessage.newBuilder();
      builder.setAddress(addressStr);
      builder.setPrivateKey(priKeyStr);
      responseObserver.onNext(builder.build());
      responseObserver.onCompleted();
    }

    @Override
    public void getRewardInfo(BytesMessage request,
        StreamObserver<NumberMessage> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getWalletSolidityApi().getRewardInfo(request, responseObserver)
      );
    }

    @Override
    public void getBrokerageInfo(BytesMessage request,
        StreamObserver<NumberMessage> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getWalletSolidityApi().getBrokerageInfo(request, responseObserver)
      );
    }

    @Override
    public void getMerkleTreeVoucherInfo(OutputPointInfo request,
        StreamObserver<IncrementalMerkleVoucherInfo> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getWalletSolidityApi()
              .getMerkleTreeVoucherInfo(request, responseObserver)
      );
    }

    @Override
    public void scanNoteByIvk(GrpcAPI.IvkDecryptParameters request,
        StreamObserver<GrpcAPI.DecryptNotes> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getWalletSolidityApi().scanNoteByIvk(request, responseObserver)
      );
    }

    @Override
    public void scanAndMarkNoteByIvk(GrpcAPI.IvkDecryptAndMarkParameters request,
        StreamObserver<GrpcAPI.DecryptNotesMarked> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getWalletSolidityApi().scanAndMarkNoteByIvk(request, responseObserver)
      );
    }

    @Override
    public void scanNoteByOvk(GrpcAPI.OvkDecryptParameters request,
        StreamObserver<GrpcAPI.DecryptNotes> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getWalletSolidityApi().scanNoteByOvk(request, responseObserver)
      );
    }

    @Override
    public void isSpend(NoteParameters request, StreamObserver<SpendResult> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getWalletSolidityApi().isSpend(request, responseObserver)
      );
    }

    @Override
    public void getMarketOrderByAccount(BytesMessage request,
        StreamObserver<MarketOrderList> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getWalletSolidityApi()
              .getMarketOrderByAccount(request, responseObserver)
      );
    }

    @Override
    public void getMarketOrderById(BytesMessage request,
        StreamObserver<MarketOrder> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getWalletSolidityApi()
              .getMarketOrderById(request, responseObserver)
      );
    }

    @Override
    public void getMarketPriceByPair(MarketOrderPair request,
        StreamObserver<MarketPriceList> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getWalletSolidityApi()
              .getMarketPriceByPair(request, responseObserver)
      );
    }

    @Override
    public void getMarketOrderListByPair(MarketOrderPair request,
        StreamObserver<MarketOrderList> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getWalletSolidityApi()
              .getMarketOrderListByPair(request, responseObserver)
      );
    }

    @Override
    public void getMarketPairList(EmptyMessage request,
        StreamObserver<MarketOrderPairList> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getWalletSolidityApi()
              .getMarketPairList(request, responseObserver)
      );
    }

    @Override
    public void scanShieldedTRC20NotesByIvk(IvkDecryptTRC20Parameters request,
        StreamObserver<DecryptNotesTRC20> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getWalletSolidityApi()
          .scanShieldedTRC20NotesByIvk(request, responseObserver)
      );
    }

    @Override
    public void scanShieldedTRC20NotesByOvk(OvkDecryptTRC20Parameters request,
        StreamObserver<DecryptNotesTRC20> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getWalletSolidityApi()
          .scanShieldedTRC20NotesByOvk(request, responseObserver)
      );
    }

    @Override
    public void isShieldedTRC20ContractNoteSpent(NfTRC20Parameters request,
        StreamObserver<NullifierResult> responseObserver) {
      walletOnPBFT.futureGet(
          () -> rpcApiService.getWalletSolidityApi()
          .isShieldedTRC20ContractNoteSpent(request, responseObserver)
      );
    }
  }
}
