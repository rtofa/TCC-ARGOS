package br.com.argos.argos_api.helpdesk.guardrail;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Light text normalization for Portuguese, used to compare an answer with its sources. */
public final class PortugueseText {

    private static final Set<String> STOPWORDS = Set.copyOf(List.of(
            "a", "ao", "aos", "aquela", "aquelas", "aquele", "aqueles", "aquilo", "as", "ate", "com", "como", "da", "das",
            "de", "dela", "delas", "dele", "deles", "depois", "do", "dos", "e", "ela", "elas", "ele", "eles", "em", "entre",
            "era", "essa", "essas", "esse", "esses", "esta", "estao", "estas", "estava", "este", "estes", "eu", "foi",
            "for", "foram", "ha", "isso", "isto", "ja", "lhe", "lhes", "mais", "mas", "me", "mesmo", "meu", "minha",
            "muito", "na", "nas", "nao", "nem", "no", "nos", "nossa", "nosso", "num", "numa", "o", "os", "ou", "para",
            "pela", "pelas", "pelo", "pelos", "por", "qual", "quando", "que", "quem", "se", "sem", "ser", "sera", "seu",
            "seus", "sua", "suas", "so", "tambem", "te", "tem", "ter", "voce", "voces", "um", "uma", "umas", "uns",
            "sao", "esta", "pode", "podem", "deve", "devem", "cada", "onde", "assim", "entao", "sobre", "apos", "antes",
            "caso", "todo", "toda", "todos", "todas", "outro", "outra", "seja", "sejam", "fica", "ficam", "possui",
            "aqui", "ali", "la", "bem", "tela", "voce", "clique", "acesse", "use", "usar", "basta", "forma", "exemplo",
            // generic words of explanations, which say nothing about the facts being checked
            "precisa", "precisar", "seguir", "seguinte", "seguintes", "abaixo", "acima", "primeiro", "passo", "passos",
            "ainda", "apenas", "somente", "fazer", "faca", "possivel", "necessario", "permite", "opcao", "opcoes",
            "algum", "alguma", "alguns", "algumas", "qualquer", "sempre", "agora", "veja", "consulte", "conforme",
            "atraves", "dentro", "partir", "vez", "vezes", "isso", "dessa", "desse", "nessa", "nesse", "nesta",
            "neste", "disso", "desta", "deste", "existe", "existem", "seguindo", "abra", "abrir",
            "the", "and", "of", "to", "in"));

    private PortugueseText() {
    }

    /** Lowercase, without accents. */
    public static String fold(String text) {
        String decomposed = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    /** Content words (no stopwords, at least 4 letters), reduced to a crude stem so inflections still match. */
    public static List<String> contentStems(String text) {
        List<String> stems = new ArrayList<>();
        for (String token : fold(text).split("[^a-z0-9]+")) {
            if (token.length() < 4 || STOPWORDS.contains(token) || token.chars().allMatch(Character::isDigit)) {
                continue;
            }
            stems.add(stem(token));
        }
        return stems;
    }

    /** First six letters: "notificações", "notificar", "notificação" all become "notifi". */
    public static String stem(String token) {
        return token.length() <= 6 ? token : token.substring(0, 6);
    }
}
