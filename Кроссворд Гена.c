#define _CRT_SECURE_NO_WARNINGS
#include <stdio.h>
#include <stdlib.h>
#include <locale.h>
#include <string.h>
#include <time.h>
#define distance 1
typedef struct {
    char* words;
    int used;
} Base;
typedef struct AVLNode {
    Base* data;
    struct AVLNode* left;
    struct AVLNode* right;
    int height;
} AVLNode;

int getHeight(AVLNode* node) {
    if (node == NULL)
        return 0;
    return node->height;
}
int getBalance(AVLNode* node) {
    if (node == NULL)
        return 0;
    return getHeight(node->left) - getHeight(node->right);
}
AVLNode* newNode(Base* data) {
    AVLNode* node = (AVLNode*)malloc(sizeof(AVLNode));
    node->data = data;
    node->left = NULL;
    node->right = NULL;
    node->height = 1; // новый узел имеет высоту 1
    return node;
}
AVLNode* rightRotate(AVLNode* y) {
    AVLNode* x = y->left;
    AVLNode* T2 = x->right;

    x->right = y;
    y->left = T2;

    y->height = max(getHeight(y->left), getHeight(y->right)) + 1;
    x->height = max(getHeight(x->left), getHeight(x->right)) + 1;

    return x;
}
AVLNode* leftRotate(AVLNode* x) {
    AVLNode* y = x->right;
    AVLNode* T2 = y->left;

    y->left = x;
    x->right = T2;

    x->height = max(getHeight(x->left), getHeight(x->right)) + 1;
    y->height = max(getHeight(y->left), getHeight(y->right)) + 1;

    return y;
}
AVLNode* insert(AVLNode* node, Base* data) {
    if (node == NULL)
        return newNode(data);

    if (strcmp(data->words, node->data->words) < 0)
        node->left = insert(node->left, data);
    else if (strcmp(data->words, node->data->words) > 0)
        node->right = insert(node->right, data);
    else
        return node; // дублирующиеся слова не допускаются

    node->height = 1 + max(getHeight(node->left), getHeight(node->right));

    int balance = getBalance(node);

    // Левый случай
    if (balance > 1 && strcmp(data->words, node->left->data->words) < 0)
        return rightRotate(node);

    // Правый случай
    if (balance < -1 && strcmp(data->words, node->right->data->words) > 0)
        return leftRotate(node);

    // Лево-правый случай
    if (balance > 1 && strcmp(data->words, node->left->data->words) > 0) {
        node->left = leftRotate(node->left);
        return rightRotate(node);
    }

    // Право-левый случай
    if (balance < -1 && strcmp(data->words, node->right->data->words) < 0) {
        node->right = rightRotate(node->right);
        return leftRotate(node);
    }

    return node;
}
void free_tree(AVLNode* root) {
    if (root != NULL) {
        free_tree(root->left);
        free_tree(root->right);
        free(root->data->words);
        free(root->data);
        free(root);
    }
}

Base* find_unused_word_with_symbol(AVLNode* root, char symbol,int promt) {
    if (root == NULL)
        return NULL;

    Base* result_left = find_unused_word_with_symbol(root->left, symbol,promt);

    if (result_left != NULL) {
        return result_left;  // Возвращаем результат из левого поддерева, если слово найдено там
    }

    if (root->data->used !=-1 && root->data->used!=promt && strchr(root->data->words, symbol) ) {
        return root->data;
    }

    return find_unused_word_with_symbol(root->right, symbol,promt);
}
int attempt_to_place_vert(int i, int j, int n, char symbol, char* word, char** crossword) {
    int k = 0;
    int amountSame = 0;

    for (int m = 0; m < strlen(word); m++) {
        if (word[m] == symbol) break;
        k++;
    }

    int start = i - k;
    int end = i + strlen(word) - k;

    if ((start < 0) || (end - 1 >= n)) {
        return 0;
    }

    k = 0;
    for (i = start; i < end; i++) {
        if (crossword[i][j] != '0' && crossword[i][j] != word[k]) {
            return 0;
        }

        if (j - 1 > -1 && (crossword[i][j - distance] != '0') && (crossword[i][j] != word[k])) {
            return 0;
        }

        if (j + 1 < n && (crossword[i][j + distance] != '0') && (crossword[i][j] != word[k])) {
            return 0;
        }
        k++;
    }

    if (end < n && crossword[end][j] != '0') {
        return 0;
    }

    if (start - 1 > -1 && crossword[start - 1][j] != '0') {
        return 0;
    }

    return 1;
}
int attempt_to_place_horiz(int i, int j, int m, char symbol, char* word, char** crossword) {
    int k = 0;
    int amountSame = 0;

    for (int m = 0; m < strlen(word); m++) {
        if (word[m] == symbol) {
            break;
        }
        k++;
    }

    int start = j - k;
    int end = j + strlen(word) - k;

    if (start < 0 || end - 1 >= m) {
        return 0;
    }

    k = 0;
    for (j = start; j < end; j++) {
        if (crossword[i][j] != '0' && crossword[i][j] != word[k]) {
            return 0;
        }

        if (i - 1 > -1 && crossword[i - distance][j] != '0' && crossword[i][j] != word[k]) {
            return 0;
        }

        if (i + 1 < m && crossword[i + distance][j] != '0' && crossword[i][j] != word[k]) {
            return 0;
        }
        k++;
    }

    if (end < m && crossword[i][end] != '0') {
        return 0;
    }

    if (start - 1 > -1 && crossword[i][start - 1] != '0') {
        return 0;
    }

    return 1;
}
void add_word_horizontal(char** crossword, char* word, int i, int start, int end) {
    for (int j = start, k = 0; j < end; j++, k++)
    {
        crossword[i][j] = word[k];
    }
}
void add_word_vertical(char** crossword, char* word, int j, int start, int end) {
    for (int i = start, k = 0; i < end; i++, k++)
    {
        crossword[i][j] = word[k];
    }
}

void print_crossw(char** crossword, int height, int wide) {
    /*Вывод кроссворда*/
    for (int i = 0; i < height; i++) {
        for (int j = 0; j < wide; j++) {
            if (crossword[i][j] == '0') printf("   ");
            else printf("%c  ", crossword[i][j]);
        }
        printf("\n");
    }
    printf("\n");
}

void free_crossw(char** crossword, int N) {
    /*Очищение кода*/
    for (int i = 0; i < N; i++) {
        free(crossword[i]);
    }
    free(crossword);
}
void place_first_word(char** crossword, int M, int N, Base* tmp) {

    int start = 0, end = 0;
    int i = rand() % N;
    int j = rand() % M;
    int len = strlen(tmp->words);
    if (M >= N)
    {

        start = (rand() % (M - len) + 1);           // рандомно назначаем клетку старта
        end = start + len;    // высчитываем клетку окончания 
        add_word_horizontal(crossword, tmp->words, i, start, end);
        tmp->used = -1;
    }
    else
    {
        start = (rand() % (N - len) + 1);           // рандомно назначаем клетку старта
        end = start + len;    // высчитываем клетку окончания 
        add_word_vertical(crossword, tmp->words, j, start, end);
        tmp->used = -1;
    }
}



int main(int argc, const char* argv[]) {
    setlocale(LC_ALL, "Russian");
    AVLNode* root = NULL;
    char word[50];
    char** crossword;
    int size_dict = 0;
    int height, wide;

    int start = 0, end = 0, flag = 0;
    Base* tmp = NULL;
    srand((unsigned int)time(NULL));

    int choose = 0;

    printf("Высота:");
    scanf("%d", &height);
    printf("Ширина:");
    scanf("%d", &wide);
    printf("\n");

    int max_len = 0, cur_len = 0;

    FILE* dictionary = fopen("dict.txt", "r");
    if (dictionary == NULL) {
        fprintf(stderr, "Ошибка открытия файла словаря.\n");
        return 1;
    }
    while (fscanf(dictionary, "%s", word) == 1) {
        Base* newBase = (Base*)malloc(sizeof(Base));
        if (strlen(word) > height - 1 && strlen(word) > wide - 1)
        {
            continue;
        }
        newBase->words = _strdup(word);
        newBase->used = 0;
        root = insert(root, newBase);
        cur_len = strlen(word);
        if (cur_len > max_len)
        {
            max_len = cur_len;
            tmp = newBase;
        }
        size_dict++;
    }

    crossword = (char**)malloc(height * sizeof(char*));
    for (int i = 0; i < height; i++) {
        crossword[i] = (char*)malloc(wide * sizeof(char));
        memset(crossword[i], '0', wide);
    }



    clock_t begin = clock();
    place_first_word(crossword, wide, height, tmp);
    // вставляем остальные слова
    for (int x = 1; x < 100; x++)
    {
        for (int I = 0; I < height; I++) {
            for (int J = 0; J < wide; J++) {
                for (int count = 0; count < size_dict; count++) { //count - количество просмотренных слов
                    if (crossword[I][J] != '0') {
                        tmp = find_unused_word_with_symbol(root, crossword[I][J],x+J);
                        int k = 0;
                        //print_crossw(crossword, height, wide);
                        if (tmp)
                        {
                            if (attempt_to_place_vert(I, J, height, crossword[I][J], tmp->words, crossword) == 1) {
                                k = 0;
                                for (int m = 0; m < strlen(tmp->words); m++) {
                                    if (tmp->words[m] == crossword[I][J]) break;
                                    k++;
                                }
                                start = I - k;
                                end = I + strlen(tmp->words) - k;
                                add_word_vertical(crossword, tmp->words, J, start, end);
                                tmp->used = -1;
                            }
                            else if (attempt_to_place_horiz(I, J, wide, crossword[I][J], tmp->words, crossword) == 1) {
                                k = 0;
                                for (int m = 0; m < strlen(tmp->words); m++) {
                                    if (tmp->words[m] == crossword[I][J]) break;
                                    k++;
                                }

                                start = J - k;
                                end = J + strlen(tmp->words) - k;
                                add_word_horizontal(crossword, tmp->words, I, start, end);
                                tmp->used = -1;
                            }
                        }
                        if (!tmp)
                        {
                            break;
                        }
                        if (tmp && tmp->used==-1)
                            break;

                        else if(tmp)
                            tmp->used = x + J;
                    }
                }

            }
        }
    }

    clock_t endt = clock();

    print_crossw(crossword, height, wide);
    free_crossw(crossword, height);
    free_tree(root);
    printf("Time: %f\n", (double)(endt - begin) / CLOCKS_PER_SEC);

    return 0;
}